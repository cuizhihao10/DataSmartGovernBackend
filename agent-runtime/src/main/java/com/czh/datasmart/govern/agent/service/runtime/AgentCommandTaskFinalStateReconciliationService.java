/**
 * @Author : Cui
 * @Date: 2026/06/26 00:00
 * @Description DataSmart Govern Backend - AgentCommandTaskFinalStateReconciliationService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentCommandTaskFinalStateCallbackSuggestionView;
import com.czh.datasmart.govern.agent.controller.dto.AgentCommandTaskFinalStateLatestReceiptView;
import com.czh.datasmart.govern.agent.controller.dto.AgentCommandTaskFinalStateReconciliationResponse;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Agent command 与 task-management 最终态对账服务。
 *
 * <p>这个服务是 command durable action 收敛阶段的关键“解释层”。前面的链路已经能做到：</p>
 * <p>1. Agent/Python Runtime 生成 command proposal 和执行前 readiness；</p>
 * <p>2. Java agent-runtime 通过 outbox、lease、sandbox admission、artifact gate 管住真实副作用入口；</p>
 * <p>3. worker 执行后写回低敏 receipt，并物化到按 commandId 查询的 receipt index。</p>
 *
 * <p>但只有 receipt 还不够。真实商业产品还需要回答：“这些 receipt 是否足以把任务台和 Agent 审计推进到
 * 成功、失败、退避或人工补偿？”本服务负责权限收口、receipt 查询、最新事实选择和响应组装；具体
 * outcome 到任务状态的业务规则拆到 {@link AgentCommandTaskFinalStateDecisionResolver}，避免 Service 继续膨胀。</p>
 *
 * <p>边界非常重要：本服务只读 receipt index，不直接修改 task-management 表，不直接调用内部回调接口，
 * 不读取命令正文、stdout/stderr、payload、SQL、prompt、模型输出或 artifact 正文。这样做可以先把
 * 对账规则稳定下来，再由后续自动回调 worker 按服务账号、签名和幂等表执行真正写入。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentCommandTaskFinalStateReconciliationService {

    public static final String PAYLOAD_POLICY =
            "LOW_SENSITIVE_FINAL_STATE_RECONCILIATION_NO_COMMAND_NO_STDIO_NO_PAYLOAD_BODY";

    private static final String QUERY_MODE = "COMMAND_ID_REQUIRED_WORKER_RECEIPT_FINAL_STATE_RECONCILIATION";
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 500;
    private static final String CALLBACK_CONTRACT =
            "POST /internal/agent-runtime/sessions/{sessionId}/runs/{runId}/tool-executions/{auditId}/async-task-status";

    private final AgentToolActionWorkerReceiptIndexService receiptIndexService;
    private final AgentRuntimeEventProjectionAccessSupport accessSupport;
    private final AgentCommandTaskFinalStateDecisionResolver decisionResolver =
            new AgentCommandTaskFinalStateDecisionResolver();

    /**
     * 基于 commandId 查询 worker receipt，并生成任务最终态对账结论。
     *
     * @param commandId 必填命令 ID。禁止为空，是为了避免管理接口被误用成全量扫描。
     * @param toolCode 可选工具编码。传入后可以避免同一个 commandId 在迁移或异常场景下被错配到不同工具。
     * @param tenantId 调用方希望进一步缩小的租户条件，最终会与可信 Header 求交集。
     * @param projectId 调用方希望进一步缩小的项目条件，PROJECT 范围下必须落在授权项目集合内。
     * @param actorId 调用方希望进一步缩小的 actor 条件，SELF 范围下会被强制收口到当前 actor。
     * @param runId 可选 Agent Run 条件，强烈建议真实调用时传入，避免跨 run 采信旧 receipt。
     * @param sessionId 可选 Agent Session 条件，用于把对账限制在当前会话。
     * @param limit 最多扫描多少条 receipt，用于异常重试场景下限制控制面开销。
     * @param accessContext gateway/permission-admin 透传的可信访问上下文。
     * @return 低敏对账响应，不包含命令、工具参数、输出正文或内部端点。
     */
    public AgentCommandTaskFinalStateReconciliationResponse reconcile(String commandId,
                                                                      String toolCode,
                                                                      String tenantId,
                                                                      String projectId,
                                                                      String actorId,
                                                                      String runId,
                                                                      String sessionId,
                                                                      Integer limit,
                                                                      AgentRuntimeEventQueryAccessContext accessContext) {
        String normalizedCommandId = requiredCommandId(commandId);
        int appliedLimit = normalizedLimit(limit);
        AgentRuntimeEventProjectionQuery scopedQuery = accessSupport.restrict(
                new AgentRuntimeEventProjectionQuery(
                        trimToNull(tenantId),
                        trimToNull(projectId),
                        trimToNull(actorId),
                        null,
                        trimToNull(runId),
                        trimToNull(sessionId),
                        null,
                        null,
                        appliedLimit
                ),
                accessContext
        );

        List<String> authorizedProjectIds = scopedQuery.normalizedAuthorizedProjectIds();
        if (authorizedProjectIds != null && authorizedProjectIds.isEmpty()) {
            AgentCommandTaskFinalStateDecision decision = AgentCommandTaskFinalStateDecision.waiting(
                    "PROJECT_SCOPE_EMPTY",
                    "当前 PROJECT 数据范围没有任何授权项目，不能采信或展示 worker receipt。",
                    List.of("FINAL_STATE_RECONCILIATION_SCOPED", "FINAL_STATE_RECONCILIATION_PROJECT_SCOPE_EMPTY"),
                    List.of("AUTHORIZED_PROJECT_SCOPE_EMPTY"),
                    List.of("请先在 permission-admin 为当前身份授予项目范围，再重新执行对账。")
            );
            return buildResponse(normalizedCommandId, toolCode, scopedQuery, appliedLimit, List.of(), null, decision);
        }

        List<AgentToolActionWorkerReceiptIndexRecord> records = receiptIndexService.queryByCommandId(
                normalizedCommandId,
                trimToNull(toolCode),
                scopedQuery,
                appliedLimit
        );
        AgentToolActionWorkerReceiptIndexRecord latest = latestReceipt(records);
        AgentCommandTaskFinalStateDecision decision = latest == null
                ? decisionResolver.noReceiptDecision()
                : decisionResolver.decide(latest);
        return buildResponse(normalizedCommandId, toolCode, scopedQuery, appliedLimit, records, latest, decision);
    }

    /**
     * 选择最新 receipt。
     *
     * <p>虽然当前内存索引按 replaySequence 升序返回，但服务层不能依赖仓储实现细节。
     * 这里显式使用 {@link AgentToolActionWorkerReceiptIndexRecord#newerThan(AgentToolActionWorkerReceiptIndexRecord)}，
     * 让未来 MySQL、ClickHouse 或审计中心实现即使改变排序，也不会影响对账结论。</p>
     */
    private AgentToolActionWorkerReceiptIndexRecord latestReceipt(List<AgentToolActionWorkerReceiptIndexRecord> records) {
        AgentToolActionWorkerReceiptIndexRecord latest = null;
        for (AgentToolActionWorkerReceiptIndexRecord record : records) {
            if (record != null && record.newerThan(latest)) {
                latest = record;
            }
        }
        return latest;
    }

    private AgentCommandTaskFinalStateReconciliationResponse buildResponse(String commandId,
                                                                           String toolCode,
                                                                           AgentRuntimeEventProjectionQuery scopedQuery,
                                                                           int appliedLimit,
                                                                           List<AgentToolActionWorkerReceiptIndexRecord> records,
                                                                           AgentToolActionWorkerReceiptIndexRecord latest,
                                                                           AgentCommandTaskFinalStateDecision decision) {
        return new AgentCommandTaskFinalStateReconciliationResponse(
                QUERY_MODE,
                PAYLOAD_POLICY,
                commandId,
                trimToNull(toolCode),
                scopedQuery.tenantId(),
                scopedQuery.projectId(),
                scopedQuery.actorId(),
                scopedQuery.runId(),
                scopedQuery.sessionId(),
                scopedQuery.normalizedAuthorizedProjectIds(),
                appliedLimit,
                records.size(),
                latest != null,
                latest == null ? null : toReceiptView(latest),
                decision.reconciliationStatus(),
                decision.reconciledTaskStatus(),
                decision.terminal(),
                decision.callbackRecommended(),
                decision.requiresManualCompensation(),
                decision.retryable(),
                toCallbackSuggestion(commandId, latest, decision),
                decision.evidenceCodes(),
                decision.issueCodes(),
                decision.recommendedActions(),
                missingCapabilities()
        );
    }

    private AgentCommandTaskFinalStateLatestReceiptView toReceiptView(AgentToolActionWorkerReceiptIndexRecord record) {
        return new AgentCommandTaskFinalStateLatestReceiptView(
                fingerprint(record.eventIdentityKey()),
                record.commandId(),
                record.taskId(),
                record.taskRunId(),
                record.executorId(),
                record.auditId(),
                record.tenantId(),
                record.projectId(),
                record.actorId(),
                record.runId(),
                record.sessionId(),
                record.toolCode(),
                record.taskStatus(),
                record.outcome(),
                record.preCheckPassed(),
                record.sideEffectExecuted(),
                record.errorCode(),
                record.replaySequence(),
                record.consumedAt(),
                record.indexedAt(),
                AgentToolActionWorkerReceiptIndexRecord.PAYLOAD_POLICY
        );
    }

    private AgentCommandTaskFinalStateCallbackSuggestionView toCallbackSuggestion(String commandId,
                                                                                  AgentToolActionWorkerReceiptIndexRecord latest,
                                                                                  AgentCommandTaskFinalStateDecision decision) {
        List<String> notes = new ArrayList<>();
        notes.add("commandId 可映射到 AgentAsyncToolTaskStatusCallbackRequest.commandId。");
        notes.add("callbackStatus 可映射到 AgentAsyncToolTaskStatusCallbackRequest.status。");
        notes.add("callbackMessage 只能作为低敏 message，不代表工具输出正文。");
        if (latest == null) {
            notes.add("当前没有 receipt，不能生成 taskId、taskRunId、executorId 或 auditId 映射。");
        } else {
            if (latest.taskId() == null) {
                notes.add("receipt index 缺少 taskId，自动回调前需要从 command outbox 或 task-management 查询补齐。");
            }
            if (latest.taskRunId() == null) {
                notes.add("receipt index 缺少 taskRunId，自动回调前需要确认本次 worker 尝试。");
            }
            if (!hasText(latest.auditId())) {
                notes.add("receipt index 缺少 auditId，不能直接调用 Agent 审计回调路径。");
            }
        }
        return new AgentCommandTaskFinalStateCallbackSuggestionView(
                decision.callbackRecommended(),
                decision.callbackStatus(),
                decision.terminal(),
                decision.callbackMessage(),
                decision.callbackErrorCode(),
                decision.outputSummary(),
                idempotencyKey(commandId, latest, decision),
                CALLBACK_CONTRACT,
                List.copyOf(notes)
        );
    }

    private List<String> missingCapabilities() {
        return List.of(
                "AUTOMATED_FINAL_STATE_CALLBACK_WORKER_WITH_IDEMPOTENCY_TABLE",
                "TASK_MANAGEMENT_DURABLE_FINAL_STATE_RECONCILIATION_HISTORY",
                "QUEUE_VISIBILITY_TIMEOUT_AND_WORKER_HEARTBEAT_ALIGNMENT",
                "BUSINESS_COMPENSATION_POLICY_BY_TOOL_TYPE"
        );
    }

    private String idempotencyKey(String commandId,
                                  AgentToolActionWorkerReceiptIndexRecord latest,
                                  AgentCommandTaskFinalStateDecision decision) {
        String sequence = latest == null || latest.replaySequence() == null
                ? "no-receipt"
                : String.valueOf(latest.replaySequence());
        return "agent-command-final-state:" + safeCode(commandId) + ":" + safeCode(decision.reconciliationStatus())
                + ":" + sequence;
    }

    private String requiredCommandId(String commandId) {
        String normalized = trimToNull(commandId);
        if (normalized == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "命令任务最终态对账必须提供 commandId，禁止无界扫描 worker receipt 索引");
        }
        if (looksSensitive(normalized)) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "commandId 疑似包含命令、SQL、prompt、URL、token 或输出通道关键字，已拒绝对账");
        }
        return normalized;
    }

    private int normalizedLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String safeCode(String value) {
        String text = trimToNull(value);
        if (text == null || looksSensitive(text)) {
            return "UNKNOWN";
        }
        return text.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_.:-]", "_");
    }

    private String fingerprint(String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed).substring(0, 16);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256，无法生成最终态对账 receipt 指纹", exception);
        }
    }

    private boolean looksSensitive(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("select ")
                || lower.contains("insert ")
                || lower.contains("update ")
                || lower.contains("delete ")
                || lower.contains("authorization:")
                || lower.contains("bearer ")
                || lower.contains("password")
                || lower.contains("token")
                || lower.contains("prompt:")
                || lower.contains("api_key")
                || lower.contains("apikey")
                || lower.contains("stdout")
                || lower.contains("stderr")
                || lower.contains("commandline")
                || lower.contains("http://")
                || lower.contains("https://")
                || lower.contains("jdbc:");
    }
}
