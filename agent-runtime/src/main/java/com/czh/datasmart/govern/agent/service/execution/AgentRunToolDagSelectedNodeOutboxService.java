/**
 * @Author : Cui
 * @Date: 2026/06/01 10:34
 * @Description DataSmart Govern Backend - AgentRunToolDagSelectedNodeOutboxService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.execution;

import com.czh.datasmart.govern.agent.controller.dto.AgentRunAsyncTaskCommandOutboxEnqueueResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentAsyncTaskCommandOutboxRecordView;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolDagExecutionDryRunRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolDagExecutionDryRunResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolDagSelectedNodeOutboxEnqueueRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolDagSelectedNodeOutboxEnqueueResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolDagExecutionDryRunItemView;
import com.czh.datasmart.govern.agent.config.AgentAsyncTaskCommandOutboxProperties;
import com.czh.datasmart.govern.agent.config.AgentRunToolDagConfirmationProperties;
import com.czh.datasmart.govern.agent.persistence.AgentRuntimeJdbcConnectionManager;
import com.czh.datasmart.govern.agent.service.execution.confirmation.AgentRunToolDagConfirmationRecord;
import com.czh.datasmart.govern.agent.service.execution.confirmation.AgentRunToolDagConfirmationStatus;
import com.czh.datasmart.govern.agent.service.execution.confirmation.AgentRunToolDagConfirmationStore;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * DAG 选中节点异步 outbox 确认入箱服务。
 *
 * <p>该服务把 4.61 的 DAG dry-run 和已有异步 command outbox 串成第一版真实执行治理闭环：
 * 调用方先查看 dry-run，再把选中节点、服务端指纹和显式确认标志提交回来；服务端重新执行 dry-run，
 * 只有仍然属于 {@link AgentToolDagExecutionDryRunAction#ASYNC_OUTBOX_ENQUEUE_PREVIEW} 的节点才允许入箱。</p>
 *
 * <p>为什么不让模型直接调用 Run 级 outbox enqueue：
 * 1. 一个 Run 可能同时包含同步、异步、等待依赖和等待审批节点，不能“一键把所有异步草案都推走”；
 * 2. DAG 节点必须按 ready 状态逐批推进，否则会绕过依赖关系；
 * 3. 前端或模型不能提交 targetEndpoint，否则攻击者可以把 Agent 变成内部网络任意请求代理；
 * 4. 指纹校验相当于一次轻量乐观锁，能够阻止过期 dry-run 被继续确认。</p>
 *
 * <p>当前边界：该服务只负责“确认后写 outbox”，不直接投递 Kafka、不创建 task-management 任务、
 * 不执行真实工具。后续仍由既有 dispatcher 和 task-management worker 完成可靠投递、租约、重试与状态回写。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentRunToolDagSelectedNodeOutboxService {

    private final AgentRunToolDagExecutionDryRunService dryRunService;
    private final AgentRunAsyncTaskCommandOutboxService outboxService;
    private final AgentRunToolDagConfirmationStore confirmationStore;
    private final AgentAsyncTaskCommandOutboxProperties outboxProperties;
    private final AgentRunToolDagConfirmationProperties confirmationProperties;
    private final Optional<AgentRuntimeJdbcConnectionManager> jdbcConnectionManager;

    /**
     * 将调用方明确确认的异步 DAG 节点写入 outbox。
     *
     * @param sessionId Agent 会话 ID，防止跨会话确认。
     * @param runId Agent Run ID，防止跨编排轮次确认。
     * @param request 节点选择、旧 dry-run 指纹和显式确认标志。
     * @param traceId 当前 HTTP 链路 ID；会传入 dry-run runtime event，便于审计回放。
     * @return 服务端重新计算的 dry-run 与最终 outbox 入箱结果。
     */
    public AgentRunToolDagSelectedNodeOutboxEnqueueResponse enqueueSelectedAsyncNodes(
            String sessionId,
            String runId,
            AgentRunToolDagSelectedNodeOutboxEnqueueRequest request,
            String traceId) {
        validateRequest(request);
        AgentRunToolDagExecutionDryRunResponse dryRun = dryRunService.dryRunDagExecution(
                sessionId,
                runId,
                new AgentRunToolDagExecutionDryRunRequest(
                        request.nodeIds(),
                        request.auditIds(),
                        request.maxNodes(),
                        false
                ),
                traceId
        );
        ensureFingerprintMatches(request.expectedDryRunFingerprint(), dryRun.selectionFingerprint());
        Set<String> selectedAuditIds = requireOnlyAsyncOutboxCandidates(dryRun);
        ensurePolicyVersionsMatch(request.expectedPolicyVersionsByAuditId(), dryRun, selectedAuditIds);
        return persistOutboxAndConfirmation(sessionId, runId, request, traceId, dryRun, selectedAuditIds);
    }

    /**
     * 在合适的持久化组合下，把“异步命令 outbox 写入”和“DAG 确认事实写入”收敛到同一个事务边界。
     *
     * <p>这里刻意只包裹真正产生副作用的两步写入，而不把前面的 dry-run、指纹校验和 policyVersion 校验也放进事务。
     * dry-run 本质上是只读预检和事件投影，它负责让调用方理解“当前哪些节点可以被确认”，不是修改命令队列。
     * 如果把大量预检逻辑放入事务，事务持有连接的时间会变长，在高并发 Agent 会话、批量 DAG 节点确认、网关重试等场景下更容易造成连接池压力。</p>
     *
     * <p>只有当 async command outbox 和 confirmation store 都明确切换到 MySQL，并且 JDBC 连接管理器已经由
     * {@code datasmart.agent-runtime.persistence.database-enabled=true} 注册时，才启用事务。这样可以避免 memory/mysql 混合模式
     * 给使用者造成“已经完全原子化”的错觉：如果其中一边仍是内存仓储，数据库事务只能覆盖 MySQL 那一边，不能保证整条链路原子提交。</p>
     */
    private AgentRunToolDagSelectedNodeOutboxEnqueueResponse persistOutboxAndConfirmation(
            String sessionId,
            String runId,
            AgentRunToolDagSelectedNodeOutboxEnqueueRequest request,
            String traceId,
            AgentRunToolDagExecutionDryRunResponse dryRun,
            Set<String> selectedAuditIds) {
        Optional<AgentRuntimeJdbcConnectionManager> manager = selectedNodeDurableTransactionManager();
        if (manager.isPresent()) {
            return manager.get().executeInTransaction(connection ->
                    persistOutboxAndConfirmationInCurrentBoundary(sessionId, runId, request, traceId, dryRun, selectedAuditIds)
            );
        }
        return persistOutboxAndConfirmationInCurrentBoundary(sessionId, runId, request, traceId, dryRun, selectedAuditIds);
    }

    /**
     * 执行 selected-node 确认的两次持久化写入。
     *
     * <p>当外层启用了 {@link AgentRuntimeJdbcConnectionManager#executeInTransaction(AgentRuntimeJdbcConnectionManager.SqlConnectionCallback)}
     * 时，MySQL outbox store 和 MySQL confirmation store 内部的 {@code executeWithConnection(...)} 会复用同一条 ThreadLocal 连接；
     * 当外层没有事务时，本方法仍保持原有 memory/local 行为不变。这个拆分让业务流程读起来更直接，也避免把事务判断散落到 outbox 或 confirmation 仓储里。</p>
     */
    private AgentRunToolDagSelectedNodeOutboxEnqueueResponse persistOutboxAndConfirmationInCurrentBoundary(
            String sessionId,
            String runId,
            AgentRunToolDagSelectedNodeOutboxEnqueueRequest request,
            String traceId,
            AgentRunToolDagExecutionDryRunResponse dryRun,
            Set<String> selectedAuditIds) {
        AgentRunAsyncTaskCommandOutboxEnqueueResponse outbox =
                outboxService.enqueueSelectedRunAsyncTaskCommands(
                        sessionId,
                        runId,
                        selectedAuditIds,
                        executionEvidenceByAuditId(sessionId, runId, dryRun, selectedAuditIds)
                );
        AgentRunToolDagConfirmationRecord confirmation = saveConfirmation(
                sessionId,
                runId,
                request,
                dryRun,
                selectedAuditIds,
                outbox,
                traceId
        );
        return new AgentRunToolDagSelectedNodeOutboxEnqueueResponse(
                sessionId,
                runId,
                dryRun.selectionFingerprint(),
                confirmation.confirmationId(),
                confirmation.expiresAt(),
                confirmation.selectedAuditIds(),
                true,
                dryRun,
                outbox,
                summaryReasons(outbox),
                recommendedActions()
        );
    }

    /**
     * 判断当前配置是否可以安全启用 selected-node durable transaction。
     *
     * <p>判断条件使用“两个 store 都是 mysql + JDBC manager 存在”，而不是只看 database-enabled。
     * 这是因为数据库总开关只代表连接池可用，不代表具体业务事实已经落库。商业化系统里这类能力开关必须保守：
     * 宁可在 memory 模式下继续保持原有行为，也不要在半持久化组合下宣称具备原子提交能力。</p>
     */
    private Optional<AgentRuntimeJdbcConnectionManager> selectedNodeDurableTransactionManager() {
        if (!"mysql".equalsIgnoreCase(outboxProperties.getStore())) {
            return Optional.empty();
        }
        if (!"mysql".equalsIgnoreCase(confirmationProperties.getStore())) {
            return Optional.empty();
        }
        return jdbcConnectionManager;
    }

    /**
     * 保存 selected-node 确认事实。
     *
     * <p>这里故意放在 outbox 写入之后：确认记录要表达的是“确认已导致哪些 command 进入可靠投递轨道”。
     * 如果 outbox 入箱失败，不能先留下 CONFIRMED 记录，否则审计台会误以为下游 command 已经形成。
     * 当前双 MySQL 配置会由外层事务边界把 outbox INSERT 与 confirmation INSERT 一起提交，进一步收敛双写风险。</p>
     */
    private AgentRunToolDagConfirmationRecord saveConfirmation(String sessionId,
                                                               String runId,
                                                               AgentRunToolDagSelectedNodeOutboxEnqueueRequest request,
                                                               AgentRunToolDagExecutionDryRunResponse dryRun,
                                                               Set<String> selectedAuditIds,
                                                               AgentRunAsyncTaskCommandOutboxEnqueueResponse outbox,
                                                               String traceId) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(Math.max(1, confirmationProperties.getConfirmationTtlSeconds()));
        List<AgentAsyncTaskCommandOutboxRecordView> outboxItems = outbox.items() == null ? List.of() : outbox.items();
        AgentAsyncTaskCommandOutboxRecordView first = outboxItems.isEmpty() ? null : outboxItems.getFirst();
        AgentRunToolDagConfirmationRecord record = new AgentRunToolDagConfirmationRecord(
                confirmationId(sessionId, runId, dryRun.selectionFingerprint(), selectedAuditIds),
                sessionId,
                runId,
                dryRun.selectionFingerprint(),
                normalizeSelectors(request.nodeIds()),
                selectedAuditIds.stream().sorted().toList(),
                collectPolicyVersions(dryRun, selectedAuditIds),
                collectDelegationEvidence(dryRun, selectedAuditIds),
                outboxItems.stream().map(AgentAsyncTaskCommandOutboxRecordView::outboxId).filter(this::hasText).toList(),
                outboxItems.stream().map(AgentAsyncTaskCommandOutboxRecordView::commandId).filter(this::hasText).toList(),
                first == null ? null : first.tenantId(),
                first == null ? null : first.projectId(),
                first == null ? null : first.workspaceId(),
                first == null ? null : first.actorId(),
                traceId,
                true,
                AgentRunToolDagConfirmationStatus.CONFIRMED,
                expiresAt,
                now,
                now
        );
        if (!confirmationProperties.isEnabled()) {
            return record;
        }
        return confirmationStore.saveIfAbsent(record);
    }

    private Map<String, AgentAsyncTaskCommandExecutionEvidence> executionEvidenceByAuditId(
            String sessionId,
            String runId,
            AgentRunToolDagExecutionDryRunResponse dryRun,
            Set<String> selectedAuditIds) {
        /*
         * 为本次 selected-node 确认生成稳定 confirmationId，并把它按 auditId 附加到每条异步 command。
         *
         * 设计上 confirmationId 不是随机流水号，而是由 sessionId、runId、selectionFingerprint 和选中 auditId
         * 共同推导出来的稳定业务 ID。这样网关重试、前端重复提交或 Python Runtime replay 时，仍然能落到同一条
         * confirmation 事实，而不会制造多份“看起来都被确认过”的审计记录。
         *
         * 这里按 auditId 组装 Map，是为了让 outbox service 只给真正入箱的节点写入证据。未来如果一个 DAG
         * 同时包含低风险自动节点和高风险人工确认节点，也可以在这里自然扩展为“不同节点携带不同证据摘要”。
         */
        String confirmationId = confirmationId(sessionId, runId, dryRun.selectionFingerprint(), selectedAuditIds);
        Map<String, AgentAsyncTaskCommandExecutionEvidence> evidenceByAuditId = new java.util.LinkedHashMap<>();
        for (AgentToolDagExecutionDryRunItemView item : dryRun.items()) {
            if (!selectedAuditIds.contains(item.auditId())) {
                continue;
            }
            evidenceByAuditId.put(item.auditId(), new AgentAsyncTaskCommandExecutionEvidence(
                    confirmationId,
                    item.serviceAuthorizationPolicyVersions(),
                    item.serviceAuthorizationDelegationEvidence()
            ));
        }
        return Map.copyOf(evidenceByAuditId);
    }

    /**
     * 校验调用方确认时携带的 permission-admin 策略版本是否仍与服务端最新 dry-run 一致。
     *
     * <p>selectionFingerprint 已经能覆盖策略版本变化，但它是整体预案摘要；这里再做一层按 auditId 的显式校验，
     * 是为了让错误信息更可解释：调用方可以知道是哪一个工具审计项的授权策略版本发生变化，而不是只看到“指纹不一致”。
     * 如果某个节点当前没有 policyVersion，说明本地预览或未启用远程授权，本方法不会强行要求版本号，避免破坏本地学习模式。</p>
     */
    private void ensurePolicyVersionsMatch(Map<String, String> expectedPolicyVersionsByAuditId,
                                           AgentRunToolDagExecutionDryRunResponse dryRun,
                                           Set<String> selectedAuditIds) {
        Map<String, String> expected = expectedPolicyVersionsByAuditId == null ? Map.of() : expectedPolicyVersionsByAuditId;
        for (AgentToolDagExecutionDryRunItemView item : dryRun.items()) {
            if (!selectedAuditIds.contains(item.auditId())) {
                continue;
            }
            String actualPolicyVersion = firstText(item.serviceAuthorizationPolicyVersions());
            if (!hasText(actualPolicyVersion)) {
                continue;
            }
            String expectedPolicyVersion = expected.get(item.auditId());
            if (!hasText(expectedPolicyVersion)) {
                throw new PlatformBusinessException(
                        PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                        "DAG selected-node 入箱缺少 auditId=" + item.auditId()
                                + " 的 expectedPolicyVersionsByAuditId；请重新 dry-run 并带回 permission-admin policyVersion"
                );
            }
            if (!MessageDigest.isEqual(
                    expectedPolicyVersion.getBytes(StandardCharsets.UTF_8),
                    actualPolicyVersion.getBytes(StandardCharsets.UTF_8))) {
                throw new PlatformBusinessException(
                        PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                        "DAG selected-node 入箱检测到 permission-admin 策略版本变化，auditId=" + item.auditId()
                                + "，expected=" + expectedPolicyVersion + "，actual=" + actualPolicyVersion
                                + "；请重新 dry-run/重新确认"
                );
            }
        }
    }

    /**
     * 校验调用方是否明确表达“确认入箱”意图。
     *
     * <p>空选择器不允许退化成“默认选中全部 ready 节点”。默认选择适合 dry-run 观察，但真实副作用必须要求
     * 调用方列出节点范围。这样即使模型错误调用确认接口，也不会意外扩大执行批次。</p>
     */
    private void validateRequest(AgentRunToolDagSelectedNodeOutboxEnqueueRequest request) {
        if (request == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST, "DAG 选中节点入箱请求不能为空");
        }
        if (!Boolean.TRUE.equals(request.confirmed())) {
            throw new PlatformBusinessException(
                    PlatformErrorCode.BAD_REQUEST,
                    "DAG 选中节点入箱必须显式传 confirmed=true；如需查看预案，请调用 dag-execution-dry-run"
            );
        }
        if (!hasAnySelector(request.nodeIds()) && !hasAnySelector(request.auditIds())) {
            throw new PlatformBusinessException(
                    PlatformErrorCode.BAD_REQUEST,
                    "DAG 选中节点入箱必须显式传 nodeIds 或 auditIds，不能默认推进整个 Run"
            );
        }
        if (!hasText(request.expectedDryRunFingerprint())) {
            throw new PlatformBusinessException(
                    PlatformErrorCode.BAD_REQUEST,
                    "DAG 选中节点入箱缺少 expectedDryRunFingerprint，请先调用 dag-execution-dry-run 获取最新预案"
            );
        }
    }

    /**
     * 对比调用方看到的旧指纹与服务端确认瞬间重新生成的新指纹。
     *
     * <p>使用 {@link MessageDigest#isEqual(byte[], byte[])} 而不是普通字符串比较，避免未来该接口暴露到更复杂
     * 的网关环境时引入不必要的时序差异。指纹不是签名，不替代认证授权；它只负责检测预案漂移。</p>
     */
    private void ensureFingerprintMatches(String expectedFingerprint, String actualFingerprint) {
        boolean matched = MessageDigest.isEqual(
                expectedFingerprint.getBytes(StandardCharsets.UTF_8),
                actualFingerprint.getBytes(StandardCharsets.UTF_8)
        );
        if (!matched) {
            throw new PlatformBusinessException(
                    PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "DAG dry-run 预案已变化，禁止使用过期指纹入箱；请重新获取 dry-run 并再次确认"
            );
        }
    }

    /**
     * 只提取仍然属于异步 outbox 候选的审计 ID。
     *
     * <p>本方法采用“整批失败”而不是“能入箱多少算多少”的策略。如果请求混入同步节点、等待依赖节点、
     * 未命中节点或超过批量上限的节点，整批都会拒绝。这样用户不会误以为一次确认完整生效，实际却只推进了一半。
     * 后续如需要部分成功模式，应设计独立的管理员批处理 API，并显式返回逐项确认结果。</p>
     */
    private Set<String> requireOnlyAsyncOutboxCandidates(AgentRunToolDagExecutionDryRunResponse dryRun) {
        Set<String> selectedAuditIds = new LinkedHashSet<>();
        List<String> rejectedSelectors = new ArrayList<>();
        for (AgentToolDagExecutionDryRunItemView item : dryRun.items()) {
            if (AgentToolDagExecutionDryRunAction.ASYNC_OUTBOX_ENQUEUE_PREVIEW.name().equals(item.dryRunAction())
                    && Boolean.TRUE.equals(item.asyncDispatchable())
                    && hasText(item.auditId())) {
                selectedAuditIds.add(item.auditId());
                continue;
            }
            rejectedSelectors.add(item.requestSelector());
        }
        if (!rejectedSelectors.isEmpty()) {
            throw new PlatformBusinessException(
                    PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "DAG 选中节点包含不能进入异步 outbox 的目标，整批已拒绝: " + rejectedSelectors
            );
        }
        if (selectedAuditIds.isEmpty()) {
            throw new PlatformBusinessException(
                    PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "DAG 选中节点当前没有可进入异步 outbox 的候选，请重新检查 dry-run 结果"
            );
        }
        return Set.copyOf(selectedAuditIds);
    }

    private List<String> summaryReasons(AgentRunAsyncTaskCommandOutboxEnqueueResponse outbox) {
        List<String> reasons = new ArrayList<>();
        reasons.add("服务端已重新执行 DAG dry-run，并确认选择指纹与调用方看到的预案一致。");
        reasons.add("本次只把显式选择且仍属于 ASYNC_OUTBOX_ENQUEUE_PREVIEW 的节点送入 outbox，没有接受外部 targetEndpoint。");
        if (outbox.duplicateCount() > 0) {
            reasons.add("部分命令已存在于 outbox，本次按稳定 commandId 幂等复用，没有重复创建下游任务。");
        }
        return List.copyOf(reasons);
    }

    private List<String> recommendedActions() {
        return List.of(
                "由 outbox dispatcher 可靠投递 command，并由 task-management Inbox 按 commandId/idempotencyKey 去重。",
                "生产启用更高自动化级别前，继续补齐 permission-admin SERVICE_ACCOUNT 委托授权、租户配额、工具限流和并发池。",
                "将确认 confirmationId 与 outbox commandId 一起写入审计时间线，便于后续查询谁确认了哪一版 dry-run 预案。"
        );
    }

    /**
     * 生成稳定确认 ID。
     *
     * <p>ID 不使用随机 UUID，是为了支持确认接口幂等：同一 session、run、fingerprint 和 auditId 集合重复提交时，
     * 应该命中同一条确认记录。这里仍然把 selectedAuditIds 排序后参与摘要，避免调用方传入顺序不同导致重复确认记录。</p>
     */
    private String confirmationId(String sessionId,
                                  String runId,
                                  String selectionFingerprint,
                                  Set<String> selectedAuditIds) {
        String canonical = sessionId + "\n" + runId + "\n" + selectionFingerprint + "\n"
                + String.join("\n", selectedAuditIds.stream().sorted().toList());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return "dag-confirmation:" + toHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 缺少 SHA-256，无法生成 DAG 确认 ID", exception);
        }
    }

    private String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format("%02x", value));
        }
        return builder.toString();
    }

    private List<String> normalizeSelectors(List<String> selectors) {
        if (selectors == null) {
            return List.of();
        }
        return selectors.stream()
                .filter(this::hasText)
                .map(String::trim)
                .distinct()
                .toList();
    }

    private List<String> collectPolicyVersions(AgentRunToolDagExecutionDryRunResponse dryRun,
                                               Set<String> selectedAuditIds) {
        return dryRun.items().stream()
                .filter(item -> selectedAuditIds.contains(item.auditId()))
                .flatMap(item -> safeList(item.serviceAuthorizationPolicyVersions()).stream())
                .filter(this::hasText)
                .distinct()
                .toList();
    }

    private List<String> collectDelegationEvidence(AgentRunToolDagExecutionDryRunResponse dryRun,
                                                   Set<String> selectedAuditIds) {
        return dryRun.items().stream()
                .filter(item -> selectedAuditIds.contains(item.auditId()))
                .flatMap(item -> safeList(item.serviceAuthorizationDelegationEvidence()).stream())
                .filter(this::hasText)
                .distinct()
                .toList();
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private String firstText(List<String> values) {
        return safeList(values).stream()
                .filter(this::hasText)
                .findFirst()
                .orElse(null);
    }

    private boolean hasAnySelector(List<String> selectors) {
        return selectors != null && selectors.stream().anyMatch(this::hasText);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
