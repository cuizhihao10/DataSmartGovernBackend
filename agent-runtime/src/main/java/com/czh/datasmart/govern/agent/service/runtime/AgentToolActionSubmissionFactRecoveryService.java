/**
 * @Author : Cui
 * @Date: 2026/06/28 22:45
 * @Description DataSmart Govern Backend - AgentToolActionSubmissionFactRecoveryService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionSubmissionFactQueryResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionSubmissionFactView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionSubmissionManualResolutionRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionSubmissionManualResolutionResponse;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Agent 工具真实提交事实恢复服务。
 *
 * <p>该服务补齐 `UNKNOWN` 状态的最小可运营闭环。前面的提交链路已经能够做到：</p>
 * <p>1. command outbox 证明“Agent 曾经产生过一个受控真实副作用命令”；</p>
 * <p>2. approval confirmation 证明“人工或策略已经确认允许执行”；</p>
 * <p>3. submission fact 证明“agent-runtime 已经开始或完成真实下游提交”；</p>
 * <p>4. task-management/data-quality 幂等键证明“重复创建下游任务可以被业务表兜住”。</p>
 *
 * <p>剩下的问题是：如果 agent-runtime 在调用 data-quality 后遇到 HTTP 超时、连接中断或响应不可解析，
 * 当前事实会进入 `UNKNOWN`。UNKNOWN 不能自动重放，因为下游可能已经创建了任务；也不能永久停住，
 * 因为运维需要一个低敏、可审计的方式把人工对账结论写回提交事实。因此本服务提供两个能力：</p>
 * <p>1. 按 commandId 查询低敏提交事实；</p>
 * <p>2. 由运维/租户管理员/平台管理员/服务账号在对账后，把 UNKNOWN 推进到 SUBMITTED 或 REJECTED。</p>
 *
 * <p>重要边界：本服务不会调用 data-quality，不会调用 task-management，不会读取 payload body，
 * 不会自动重放真实副作用。它只写“人工对账后的低敏事实结论”，让后续重复请求能够命中稳定终态，
 * 并让运营侧知道下一步该查看下游任务还是重新发起新的受控 command。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentToolActionSubmissionFactRecoveryService {

    public static final String PAYLOAD_POLICY =
            "LOW_SENSITIVE_SUBMISSION_RECOVERY_NO_PAYLOAD_NO_PROMPT_NO_SQL_NO_ENDPOINT";
    private static final String QUERY_MODE = "COMMAND_ID_REQUIRED_SUBMISSION_FACT_RECOVERY";
    private static final String MANUAL_SUBMITTED_OUTCOME = "MANUAL_RECONCILIATION_CONFIRMED_SUBMITTED";
    private static final String MANUAL_REJECTED_OUTCOME = "MANUAL_RECONCILIATION_CONFIRMED_NOT_SUBMITTED";

    private final AgentToolActionSubmissionFactStore factStore;
    private final AgentRuntimeEventProjectionAccessSupport accessSupport;

    /**
     * 按 commandId 查询真实提交事实。
     *
     * <p>查询接口故意不提供列表分页。对于提交事实这种跨服务副作用控制面数据，产品早期更安全的形态是：
     * 调用方必须已经从任务、事件、告警或日志里拿到了具体 commandId，才能进入对账详情。
     * 后续如果要做运营工作台列表，也应该先补角色权限、审计日志、状态索引和查询速率限制。</p>
     *
     * @param commandId 必填 commandId，禁止为空或疑似包含敏感正文。
     * @param tenantId 可选租户过滤，最终会和 Header 数据范围求交集。
     * @param projectId 可选项目过滤，PROJECT 范围下必须落在授权集合内。
     * @param actorId 可选 actor 过滤，SELF 范围下会被强制收口。
     * @param runId 可选 run 过滤，避免跨运行误读事实。
     * @param sessionId 可选 session 过滤，避免跨会话误读事实。
     * @param accessContext gateway/permission-admin 透传的可信访问上下文。
     * @return 低敏查询响应。
     */
    public AgentToolActionSubmissionFactQueryResponse query(String commandId,
                                                            String tenantId,
                                                            String projectId,
                                                            String actorId,
                                                            String runId,
                                                            String sessionId,
                                                            AgentRuntimeEventQueryAccessContext accessContext) {
        String normalizedCommandId = requiredText(commandId, "commandId", 180);
        AgentRuntimeEventProjectionQuery scopedQuery = scopedQuery(
                tenantId, projectId, actorId, runId, sessionId, accessContext);
        return factStore.findByCommandId(normalizedCommandId)
                .filter(record -> visible(record, scopedQuery))
                .map(record -> new AgentToolActionSubmissionFactQueryResponse(
                        QUERY_MODE,
                        PAYLOAD_POLICY,
                        normalizedCommandId,
                        scopedQuery.tenantId(),
                        scopedQuery.projectId(),
                        scopedQuery.actorId(),
                        scopedQuery.runId(),
                        scopedQuery.sessionId(),
                        scopedQuery.normalizedAuthorizedProjectIds(),
                        true,
                        toView(record),
                        evidenceFor(record),
                        issuesFor(record),
                        actionsFor(record)
                ))
                .orElseGet(() -> notVisibleOrMissing(normalizedCommandId, scopedQuery));
    }

    /**
     * 人工对账并恢复 UNKNOWN 提交事实。
     *
     * <p>该方法只允许处理 UNKNOWN。原因是 SUBMITTED/REJECTED 已经是稳定可复用终态，
     * 贸然覆盖会破坏幂等语义；SUBMITTING 则表示可能仍有请求在途，直接人工覆盖容易把仍在执行的下游调用变成双写风险。
     * 如果生产环境需要处理长期 SUBMITTING，建议后续单独做“超时租约 + 对账升级”流程，而不是复用 UNKNOWN 恢复接口。</p>
     *
     * @param commandId 必填 commandId。
     * @param request 人工对账请求；默认 dryRun=true。
     * @param accessContext 可信访问上下文，用于角色和数据范围校验。
     * @return 人工恢复响应；dryRun 时只返回预览。
     */
    public AgentToolActionSubmissionManualResolutionResponse resolveUnknown(
            String commandId,
            AgentToolActionSubmissionManualResolutionRequest request,
            AgentRuntimeEventQueryAccessContext accessContext) {
        String normalizedCommandId = requiredText(commandId, "commandId", 180);
        requirePrivilegedOperator(accessContext);
        AgentRuntimeEventProjectionQuery scopedQuery = scopedQuery(
                request == null ? null : request.tenantId(),
                request == null ? null : request.projectId(),
                request == null ? null : request.actorId(),
                request == null ? null : request.runId(),
                request == null ? null : request.sessionId(),
                accessContext
        );
        AgentToolActionSubmissionFactRecord before = factStore.findByCommandId(normalizedCommandId)
                .orElseThrow(() -> new PlatformBusinessException(PlatformErrorCode.NOT_FOUND,
                        "未找到可人工对账的 Agent 工具真实提交事实，commandId=" + normalizedCommandId));
        if (!visible(before, scopedQuery)) {
            throw new PlatformBusinessException(PlatformErrorCode.TENANT_SCOPE_DENIED,
                    "当前身份不能恢复该 Agent 工具真实提交事实，或请求条件与事实作用域不一致");
        }
        if (before.status() != AgentToolActionSubmissionStatus.UNKNOWN) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "只有 UNKNOWN 提交事实允许人工对账恢复，当前 status=" + before.status());
        }

        AgentToolActionSubmissionStatus targetStatus = targetStatus(request == null ? null : request.targetStatus());
        AgentToolActionSubmissionFactRecord after = buildResolvedRecord(before, request, targetStatus);
        boolean dryRun = request == null || !Boolean.FALSE.equals(request.dryRun());
        AgentToolActionSubmissionFactRecord saved = dryRun ? after : factStore.save(after);
        return new AgentToolActionSubmissionManualResolutionResponse(
                true,
                dryRun,
                !dryRun,
                normalizedCommandId,
                toView(before),
                toView(saved),
                List.of("SUBMISSION_FACT_FOUND", "UNKNOWN_FACT_MANUAL_RECONCILIATION_READY"),
                targetStatus == AgentToolActionSubmissionStatus.SUBMITTED
                        ? List.of()
                        : List.of("DOWNSTREAM_TASK_NOT_CREATED_CONFIRMED"),
                targetStatus == AgentToolActionSubmissionStatus.SUBMITTED
                        ? List.of("继续在 task-management 中跟踪下游治理任务状态。")
                        : List.of("如仍需执行治理，请重新发起新的受控 command，禁止复用旧 UNKNOWN command 自动重放。"),
                dryRun
                        ? "人工对账恢复预览已生成，未修改提交事实。"
                        : "人工对账结论已写入提交事实，后续重复提交将复用该低敏终态。"
        );
    }

    private AgentToolActionSubmissionFactQueryResponse notVisibleOrMissing(String commandId,
                                                                           AgentRuntimeEventProjectionQuery scopedQuery) {
        return new AgentToolActionSubmissionFactQueryResponse(
                QUERY_MODE,
                PAYLOAD_POLICY,
                commandId,
                scopedQuery.tenantId(),
                scopedQuery.projectId(),
                scopedQuery.actorId(),
                scopedQuery.runId(),
                scopedQuery.sessionId(),
                scopedQuery.normalizedAuthorizedProjectIds(),
                false,
                null,
                List.of("SUBMISSION_FACT_NOT_VISIBLE_OR_MISSING"),
                List.of("SUBMISSION_FACT_NOT_FOUND_IN_CURRENT_SCOPE"),
                List.of("请确认 commandId、租户、项目、actor、runId、sessionId 与当前权限范围是否一致。")
        );
    }

    private AgentRuntimeEventProjectionQuery scopedQuery(String tenantId,
                                                         String projectId,
                                                         String actorId,
                                                         String runId,
                                                         String sessionId,
                                                         AgentRuntimeEventQueryAccessContext accessContext) {
        return accessSupport.restrict(
                new AgentRuntimeEventProjectionQuery(
                        trimToNull(tenantId),
                        trimToNull(projectId),
                        trimToNull(actorId),
                        null,
                        trimToNull(runId),
                        trimToNull(sessionId),
                        null,
                        null,
                        1
                ),
                accessContext
        );
    }

    private boolean visible(AgentToolActionSubmissionFactRecord record, AgentRuntimeEventProjectionQuery scopedQuery) {
        if (record == null || scopedQuery == null) {
            return false;
        }
        List<String> authorizedProjectIds = scopedQuery.normalizedAuthorizedProjectIds();
        if (authorizedProjectIds != null) {
            if (authorizedProjectIds.isEmpty()) {
                return false;
            }
            if (!authorizedProjectIds.contains(record.projectId())) {
                return false;
            }
        }
        return matches(scopedQuery.tenantId(), record.tenantId())
                && matches(scopedQuery.projectId(), record.projectId())
                && matches(scopedQuery.actorId(), record.actorId())
                && matches(scopedQuery.runId(), record.runId())
                && matches(scopedQuery.sessionId(), record.sessionId());
    }

    private boolean matches(String expected, String actual) {
        return expected == null || expected.isBlank() || expected.equals(actual);
    }

    private void requirePrivilegedOperator(AgentRuntimeEventQueryAccessContext accessContext) {
        if (accessContext == null || !accessContext.hasIdentity()) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "人工恢复 Agent 工具提交事实需要可信身份 Header");
        }
        String role = accessContext.normalizedRole();
        if (!List.of("OPERATOR", "TENANT_ADMINISTRATOR", "PLATFORM_ADMINISTRATOR", "SERVICE_ACCOUNT").contains(role)) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "当前角色不允许人工恢复 Agent 工具提交事实，role=" + role);
        }
    }

    private AgentToolActionSubmissionStatus targetStatus(String rawStatus) {
        String normalized = rawStatus == null ? "" : rawStatus.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "SUBMITTED" -> AgentToolActionSubmissionStatus.SUBMITTED;
            case "REJECTED" -> AgentToolActionSubmissionStatus.REJECTED;
            default -> throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "人工对账 targetStatus 仅支持 SUBMITTED 或 REJECTED");
        };
    }

    private AgentToolActionSubmissionFactRecord buildResolvedRecord(AgentToolActionSubmissionFactRecord before,
                                                                    AgentToolActionSubmissionManualResolutionRequest request,
                                                                    AgentToolActionSubmissionStatus targetStatus) {
        if (targetStatus == AgentToolActionSubmissionStatus.SUBMITTED
                && (request == null || request.downstreamTaskId() == null || request.downstreamTaskId() <= 0)) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "确认下游已创建任务时必须提供 downstreamTaskId");
        }
        if (targetStatus == AgentToolActionSubmissionStatus.REJECTED
                && request != null && request.downstreamTaskId() != null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "确认下游未创建任务时不能提供 downstreamTaskId，避免制造错误关联");
        }
        String operatorNote = safeOptionalText(request == null ? null : request.operatorNote(), 220, "operatorNote");
        String reasonCode = safeOptionalCode(request == null ? null : request.resolutionReasonCode(), 120);
        String outcome = safeOptionalCode(request == null ? null : request.outcome(), 120);
        String taskStatus = safeOptionalCode(request == null ? null : request.downstreamTaskStatus(), 80);
        if (targetStatus == AgentToolActionSubmissionStatus.SUBMITTED) {
            return before.transitionTo(
                    AgentToolActionSubmissionStatus.SUBMITTED,
                    true,
                    true,
                    firstText(outcome, MANUAL_SUBMITTED_OUTCOME),
                    request.downstreamTaskId(),
                    firstText(taskStatus, "PENDING"),
                    null,
                    reasonCode == null ? List.of() : List.of(reasonCode),
                    List.of("继续在 task-management 中跟踪下游治理任务状态。"),
                    firstText(operatorNote, "人工对账确认下游任务已创建，提交事实已恢复为 SUBMITTED。"),
                    Instant.now()
            );
        }
        return before.transitionTo(
                AgentToolActionSubmissionStatus.REJECTED,
                true,
                false,
                firstText(outcome, MANUAL_REJECTED_OUTCOME),
                null,
                null,
                firstText(reasonCode, "MANUAL_RECONCILIATION_NOT_SUBMITTED"),
                List.of(firstText(reasonCode, "DOWNSTREAM_TASK_NOT_FOUND")),
                List.of("如仍需执行治理，请重新发起新的受控 command，禁止复用旧 UNKNOWN command 自动重放。"),
                firstText(operatorNote, "人工对账确认下游任务未创建，提交事实已恢复为 REJECTED。"),
                Instant.now()
        );
    }

    private AgentToolActionSubmissionFactView toView(AgentToolActionSubmissionFactRecord record) {
        return new AgentToolActionSubmissionFactView(
                fingerprint(record.submissionIdentityKey()),
                record.commandId(),
                record.idempotencyKey(),
                record.sessionId(),
                record.runId(),
                record.auditId(),
                record.toolCode(),
                record.tenantId(),
                record.projectId(),
                record.actorId(),
                fingerprint(record.payloadReference()),
                fingerprint(record.confirmationId()),
                record.policyVersion(),
                record.targetService(),
                record.targetEndpoint(),
                record.status().name(),
                record.sideEffectStarted(),
                record.sideEffectExecuted(),
                record.outcome(),
                record.downstreamTaskId(),
                record.downstreamTaskStatus(),
                record.errorCode(),
                record.issueCodes(),
                record.recommendedActions(),
                record.lowSensitiveMessage(),
                record.firstSubmittedAt(),
                record.lastUpdatedAt(),
                AgentToolActionSubmissionFactRecord.PAYLOAD_POLICY,
                record.status() == AgentToolActionSubmissionStatus.UNKNOWN,
                record.status().reusableResponse()
        );
    }

    private List<String> evidenceFor(AgentToolActionSubmissionFactRecord record) {
        return switch (record.status()) {
            case SUBMITTED -> List.of("SUBMISSION_FACT_TERMINAL_SUBMITTED", "DOWNSTREAM_TASK_ID_PRESENT");
            case REJECTED -> List.of("SUBMISSION_FACT_TERMINAL_REJECTED");
            case UNKNOWN -> List.of("SUBMISSION_FACT_UNKNOWN_REQUIRES_MANUAL_RECONCILIATION");
            case SUBMITTING -> List.of("SUBMISSION_FACT_SUBMITTING_SIDE_EFFECT_MAY_BE_IN_FLIGHT");
        };
    }

    private List<String> issuesFor(AgentToolActionSubmissionFactRecord record) {
        return switch (record.status()) {
            case UNKNOWN -> List.of("SUBMISSION_STATUS_UNKNOWN");
            case SUBMITTING -> List.of("SUBMISSION_STILL_IN_PROGRESS");
            default -> List.of();
        };
    }

    private List<String> actionsFor(AgentToolActionSubmissionFactRecord record) {
        return switch (record.status()) {
            case SUBMITTED -> List.of("继续跟踪 task-management 下游任务状态。");
            case REJECTED -> List.of("如仍需执行治理，请重新发起新的受控 command。");
            case UNKNOWN -> List.of("先按 idempotencyKey 对账 data-quality/task-management，再执行人工恢复。");
            case SUBMITTING -> List.of("等待当前提交完成；如长期停留，再进入超时对账流程。");
        };
    }

    private String requiredText(String value, String fieldName, int maxLength) {
        String text = safeOptionalText(value, maxLength, fieldName);
        if (text == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST, fieldName + " 不能为空");
        }
        return text;
    }

    private String safeOptionalText(String value, int maxLength, String fieldName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (looksSensitive(text)) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    fieldName + " 疑似包含 SQL、prompt、凭据、URL、token 或正文内容，已拒绝写入提交事实");
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private String safeOptionalCode(String value, int maxLength) {
        String text = safeOptionalText(value, maxLength, "code");
        if (text == null) {
            return null;
        }
        String normalized = text.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_.:-]", "_");
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private String firstText(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String fingerprint(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of()
                    .formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 16);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256，无法生成提交事实短指纹", exception);
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
                || lower.contains("payload")
                || lower.contains("http://")
                || lower.contains("https://")
                || lower.contains("jdbc:");
    }
}
