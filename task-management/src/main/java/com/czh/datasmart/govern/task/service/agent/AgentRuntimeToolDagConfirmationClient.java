/**
 * @Author : Cui
 * @Date: 2026/06/01 23:36
 * @Description DataSmart Govern Backend - AgentRuntimeToolDagConfirmationClient.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.task.config.AgentAsyncToolWorkerProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Agent Runtime DAG selected-node 确认记录查询客户端。
 *
 * <p>该客户端服务于 task-management worker 的“执行前二次复核”：
 * worker 在真正调用 data-sync、data-quality 或未来更多工具适配器之前，需要回到 agent-runtime 查询原始确认记录，
 * 确认当前任务确实来自被用户/策略确认过的 DAG 节点，而不是只相信 Kafka 消息或 task.params 中的本地字段。</p>
 *
 * <p>为什么把查询封装成独立客户端：
 * 1. HTTP 路由、Header、PlatformApiResponse 解包属于集成边界，不应该散落在 pre-check 业务规则里；
 * 2. 后续如果 agent-runtime 增加服务账号签名、mTLS、内部网关、重试或熔断，只需要改这一处；
 * 3. 单元测试可以直接 mock 本客户端，把“网络是否可用”和“复核规则是否正确”拆开验证。</p>
 */
@Component
@RequiredArgsConstructor
public class AgentRuntimeToolDagConfirmationClient {

    private final AgentAsyncToolWorkerProperties properties;
    private final RestClient.Builder restClientBuilder;

    /**
     * 按 confirmationId 查询确认快照。
     *
     * <p>调用路径使用 agent-runtime 已有审计查询路由，而不是直接读它的内存 store 或数据库。
     * 这样 task-management 只能看到 agent-runtime 愿意暴露的低敏确认视图，并继续复用 agent-runtime 自己的数据范围收口逻辑。</p>
     *
     * @param payload 当前 worker 已解析出的异步工具任务快照，提供 sessionId、runId、tenantId、actorId 和 traceId
     * @return agent-runtime 返回的低敏确认快照
     */
    public AgentRuntimeToolDagConfirmationView fetchConfirmation(AgentAsyncToolResolvedPayload payload) {
        try {
            PlatformApiResponse<AgentRuntimeToolDagConfirmationView> response = restClientBuilder
                    .baseUrl(properties.getAgentRuntimeBaseUrl())
                    .build()
                    .get()
                    .uri("/agent-runtime/sessions/{sessionId}/runs/{runId}/tool-executions/dag-confirmations/{confirmationId}",
                            payload.sessionId(), payload.runId(), payload.confirmationId())
                    .headers(headers -> applyHeaders(headers, payload))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            return unwrap(response, payload);
        } catch (RestClientException exception) {
            throw new IllegalStateException("调用 agent-runtime DAG selected-node 确认查询接口失败: "
                    + exception.getMessage(), exception);
        }
    }

    /**
     * 构造服务间调用 Header。
     *
     * <p>当前项目尚未完成统一的服务账号签名体系，因此这里先显式传递 SOURCE_SERVICE、SERVICE_ACCOUNT 角色、
     * traceId、tenantId 和 actorId。agent-runtime 的查询服务会基于这些 Header 构造访问上下文。
     * 生产化之后，这些 Header 不能由外部客户端伪造，应由 gateway、服务网格或内部调用 SDK 注入并校验签名。</p>
     */
    private void applyHeaders(HttpHeaders headers, AgentAsyncToolResolvedPayload payload) {
        headers.set(PlatformContextHeaders.SOURCE_SERVICE, "task-management");
        headers.set(PlatformContextHeaders.ACTOR_ROLE, "SERVICE_ACCOUNT");
        headers.set(PlatformContextHeaders.ACTOR_TYPE, "SERVICE_ACCOUNT");
        if (payload.traceId() != null && !payload.traceId().isBlank()) {
            headers.set(PlatformContextHeaders.TRACE_ID, payload.traceId().trim());
        }
        if (payload.tenantId() != null) {
            headers.set(PlatformContextHeaders.TENANT_ID, String.valueOf(payload.tenantId()));
        }
        headers.set(PlatformContextHeaders.ACTOR_ID, numericActorIdOrFallback(payload.actorId()));
    }

    /**
     * 将 actorId 转换成 agent-runtime 当前 Header 解析器可接受的数字形式。
     *
     * <p>task payload 中的 actorId 在多数场景下来自平台用户 ID，应该是数字；但 Agent 编排、服务账号或外部系统
     * 未来可能传入非数字主体编码。为了不让 Header 解析在网络边界提前失败，这里在无法解析时退回到 0，
     * 同时仍通过 SERVICE_ACCOUNT 角色表达“这是 task-management 代表平台内部执行的复核查询”。</p>
     */
    private String numericActorIdOrFallback(String actorId) {
        if (actorId == null || actorId.isBlank()) {
            return "0";
        }
        try {
            long parsed = Long.parseLong(actorId.trim());
            return String.valueOf(Math.max(parsed, 0L));
        } catch (NumberFormatException ignored) {
            return "0";
        }
    }

    private AgentRuntimeToolDagConfirmationView unwrap(
            PlatformApiResponse<AgentRuntimeToolDagConfirmationView> response,
            AgentAsyncToolResolvedPayload payload) {
        if (response == null) {
            throw new IllegalStateException("agent-runtime 返回空确认查询响应，confirmationId=" + payload.confirmationId());
        }
        if (response.getCode() == null || response.getCode() != 0) {
            throw new IllegalStateException("agent-runtime 确认查询失败，code=" + response.getCode()
                    + ", reason=" + response.getReason() + ", message=" + response.getMessage());
        }
        if (response.getData() == null) {
            throw new IllegalStateException("agent-runtime 确认查询成功但 data 为空，confirmationId=" + payload.confirmationId());
        }
        return response.getData();
    }
}
