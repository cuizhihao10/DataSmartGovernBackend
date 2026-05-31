/**
 * @Author : Cui
 * @Date: 2026/06/01 10:34
 * @Description DataSmart Govern Backend - AgentRunToolDagSelectedNodeOutboxService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.execution;

import com.czh.datasmart.govern.agent.controller.dto.AgentRunAsyncTaskCommandOutboxEnqueueResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolDagExecutionDryRunRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolDagExecutionDryRunResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolDagSelectedNodeOutboxEnqueueRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolDagSelectedNodeOutboxEnqueueResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolDagExecutionDryRunItemView;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
        AgentRunAsyncTaskCommandOutboxEnqueueResponse outbox =
                outboxService.enqueueSelectedRunAsyncTaskCommands(sessionId, runId, selectedAuditIds);
        return new AgentRunToolDagSelectedNodeOutboxEnqueueResponse(
                sessionId,
                runId,
                dryRun.selectionFingerprint(),
                true,
                dryRun,
                outbox,
                summaryReasons(outbox),
                recommendedActions()
        );
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
                "生产启用更高自动化级别前，继续补齐 permission-admin SERVICE_ACCOUNT 委托授权、租户配额、工具限流和并发池。"
        );
    }

    private boolean hasAnySelector(List<String> selectors) {
        return selectors != null && selectors.stream().anyMatch(this::hasText);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
