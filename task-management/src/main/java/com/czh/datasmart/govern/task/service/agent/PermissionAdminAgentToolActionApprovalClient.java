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
            return unwrap(response);
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
            PlatformApiResponse<AgentToolActionControlledApprovalEvaluationResult> response) {
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
        return response.getData();
    }

    private String safeText(String value) {
        return value == null ? "" : value.trim();
    }
}
