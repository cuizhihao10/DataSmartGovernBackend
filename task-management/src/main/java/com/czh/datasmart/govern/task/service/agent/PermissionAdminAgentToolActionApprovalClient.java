/**
 * @Author : Cui
 * @Date: 2026/06/11 23:30
 * @Description DataSmart Govern Backend - PermissionAdminAgentToolActionApprovalClient.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.task.config.AgentAsyncToolWorkerProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * permission-admin Agent 工具动作审批事实评估客户端。
 *
 * <p>该客户端只服务 `AGENT_TOOL_ACTION_CONTROLLED` dry-run/pre-check 链路。
 * 它把 task-management 的低敏任务快照转换为 permission-admin `/tool-action-approvals/evaluate` 请求，
 * 并把通信失败向上抛给 dry-run dispatcher，由 dispatcher 决定 defer、fail-open 或 fail-closed。</p>
 *
 * <p>为什么不复用历史 `PermissionAdminAgentAsyncToolAuthorizationClient`：
 * 历史客户端回答“服务账号是否有权执行已确认异步工具”；本客户端回答“审批事实 ID 是否真实存在且绑定当前工具动作”。
 * 两者虽然都访问 permission-admin，但业务问题不同，拆开后更容易维护和审计。</p>
 */
@Component
@RequiredArgsConstructor
public class PermissionAdminAgentToolActionApprovalClient {

    private static final Pattern SAFE_FACT_ID_PATTERN = Pattern.compile("[A-Za-z0-9:_.\\-]{1,160}");
    private static final Pattern SAFE_SCOPE_ID_PATTERN = Pattern.compile("[A-Za-z0-9:_.\\-]{1,160}");
    private static final Pattern SAFE_ACTION_FINGERPRINT_PATTERN = Pattern.compile("[A-Za-z0-9:_.\\-]{1,256}");
    private static final List<String> APPROVED_EVIDENCE_CODES = List.of(
            "APPROVAL_FACT_FOUND",
            "APPROVAL_FACT_SCOPE_VERIFIED",
            "APPROVAL_FACT_STATUS_APPROVED"
    );

    private final AgentAsyncToolWorkerProperties properties;
    private final RestClient.Builder restClientBuilder;

    /**
     * 调用 permission-admin 评估审批事实。
     *
     * @param request 当前受控工具动作的低敏审批评估请求。
     * @return permission-admin 的审批事实评估结果。
     */
    public AgentToolActionControlledApprovalEvaluationResult evaluate(
            AgentToolActionControlledApprovalEvaluationRequest request) {
        validateRequest(request);
        try {
            PlatformApiResponse<AgentToolActionControlledApprovalEvaluationResult> response = restClientBuilder
                    .requestFactory(requestFactory())
                    .build()
                    .post()
                    .uri(properties.getControlledActionApprovalEvaluateUrl())
                    .header(PlatformContextHeaders.TRACE_ID, safeText(request.traceId()))
                    .header(PlatformContextHeaders.SOURCE_SERVICE, "task-management")
                    .body(request)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            return unwrap(response, request);
        } catch (RestClientException exception) {
            throw new IllegalStateException("调用 permission-admin 审批事实评估接口失败: " + exception.getMessage(), exception);
        }
    }

    private SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofMillis(Math.max(1L, properties.getControlledActionApprovalTimeoutMs()));
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        return requestFactory;
    }

    private AgentToolActionControlledApprovalEvaluationResult unwrap(
            PlatformApiResponse<AgentToolActionControlledApprovalEvaluationResult> response,
            AgentToolActionControlledApprovalEvaluationRequest request) {
        if (response == null) {
            throw new IllegalStateException("permission-admin 返回空审批事实评估响应");
        }
        if (response.getCode() == null || response.getCode() != 0) {
            throw new IllegalStateException("permission-admin 审批事实评估失败，reason=" + response.getReason()
                    + ", message=" + response.getMessage());
        }
        if (response.getData() == null) {
            throw new IllegalStateException("permission-admin 审批事实评估成功但 data 为空");
        }
        AgentToolActionControlledApprovalEvaluationResult result = response.getData();
        validateEvaluationResult(request, result);
        return result;
    }

    /**
     * A controlled-action approval request is never a best-effort telemetry
     * payload. Validate its complete dual-subject scope before constructing an
     * HTTP client so an incomplete client fact cannot reach permission-admin or
     * become an implicit allow decision.
     */
    private void validateRequest(AgentToolActionControlledApprovalEvaluationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("approval evaluation request 不能为空");
        }
        requireSafeFactId(request.approvalFactId());
        requirePositive(request.tenantId(), "tenantId");
        requirePositive(request.applicationId(), "applicationId");
        requirePositive(request.projectId(), "projectId");
        requireSafeScopeId(request.userId(), "userId");
        requireSafeScopeId(request.actorId(), "actorId");
        requireSafeScopeId(request.agentId(), "agentId");
        requireSafeScopeId(request.sessionId(), "sessionId");
        requireSafeScopeId(request.runId(), "runId");
        requireSafeScopeId(request.delegationId(), "delegationId");
        requireSafeScopeId(request.commandId(), "commandId");
        requireSafeScopeId(request.toolCode(), "toolCode");
        requireSafeActionFingerprint(request.actionFingerprint());
        requireSafeScopeId(request.requestedPolicyVersion(), "requestedPolicyVersion");
    }

    /**
     * permission-admin's approved response is authoritative only when it is
     * self-consistent with the exact fact and policy requested here. A stray or
     * malformed approved response must fail closed before the dispatcher can
     * consider a side effect.
     */
    private void validateEvaluationResult(AgentToolActionControlledApprovalEvaluationRequest request,
                                          AgentToolActionControlledApprovalEvaluationResult result) {
        if (result == null) {
            throw new IllegalArgumentException("permission-admin 返回空审批事实结果");
        }
        if (!Objects.equals(normalize(request.approvalFactId()), normalize(result.approvalFactId()))) {
            throw new IllegalArgumentException("permission-admin 返回的 approvalFactId 与请求不一致");
        }
        if (!Boolean.TRUE.equals(result.approved())) {
            return;
        }
        if (Boolean.TRUE.equals(result.retryable())
                || !"APPROVED".equals(result.decision())
                || !"APPROVED".equals(result.status())
                || !Objects.equals(normalize(request.requestedPolicyVersion()), normalize(result.policyVersion()))
                || !result.evidenceCodes().containsAll(APPROVED_EVIDENCE_CODES)) {
            throw new IllegalArgumentException("permission-admin 的 APPROVED 审批结果缺少一致的范围或策略证据");
        }
    }

    private void requirePositive(Long value, String fieldName) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(fieldName + " 必须大于 0");
        }
    }

    private void requireSafeFactId(String value) {
        String normalized = normalize(value);
        if (normalized == null || !SAFE_FACT_ID_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("approvalFactId 必须是安全的低敏事实 ID");
        }
    }

    private void requireSafeScopeId(String value, String fieldName) {
        String normalized = normalize(value);
        if (normalized == null || !SAFE_SCOPE_ID_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(fieldName + " 必须是安全的低敏范围 ID");
        }
    }

    private void requireSafeActionFingerprint(String value) {
        String normalized = normalize(value);
        if (normalized == null || !SAFE_ACTION_FINGERPRINT_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("actionFingerprint 必须是安全的低敏动作指纹");
        }
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String safeText(String value) {
        return value == null ? "" : value.trim();
    }
}
