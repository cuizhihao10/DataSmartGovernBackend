/**
 * @Author : Cui
 * @Date: 2026/06/11 22:20
 * @Description DataSmart Govern Backend - AgentRuntimeToolActionControlledReceiptClient.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.task.config.AgentAsyncToolWorkerProperties;
import com.czh.datasmart.govern.task.controller.dto.TaskActorContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

/**
 * agent-runtime 受控工具动作 dry-run receipt 回写客户端。
 *
 * <p>task-management 是任务状态机的拥有者，但 Agent timeline 和 runtime event replay 归 agent-runtime 管理。
 * 因此 dry-run dispatcher 完成认领、defer 或 fail 后，需要通过该客户端把低敏 receipt 回写到 agent-runtime。
 * 这延续了“微服务不共享数据库、不跨模块直接写表”的边界：task-management 只发服务间事实，agent-runtime
 * 决定如何投影、脱敏、展示和后续持久化。</p>
 *
 * <p>可靠性策略：
 * 当前阶段 receipt 回写是 best-effort + fail-open。原因是 dry-run receipt 主要服务 timeline 可见性，
 * 还不是任务状态机的唯一事实源；如果 agent-runtime 暂时不可用，不应该让 task-management 已经完成的
 * defer/fail 状态被回滚或卡死。等后续进入强审计阶段，应把 receipt 升级为 task-management outbox，
 * 通过重试、死信和补偿任务保证最终一致。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentRuntimeToolActionControlledReceiptClient {

    private final AgentAsyncToolWorkerProperties properties;
    private final RestClient.Builder restClientBuilder;

    /**
     * 发布一条受控工具动作 dry-run receipt。
     *
     * @param payload 已通过 resolver 解析出的低敏任务快照。
     * @param taskRunId task-management 当前认领 run ID。
     * @param outcome dry-run 结果。
     * @param preCheckPassed 前置复核是否通过。
     * @param message 低敏结果说明。
     * @param errorCode 失败或阻断分类码。
     * @param recommendedActions 面向运维的低敏建议。
     * @param actorContext 当前内部调用上下文，用于 trace/actor 透传。
     * @return receipt 投递结果，供 dry-run 调试入口返回。
     */
    public AgentToolActionControlledReceiptDelivery publishDryRunReceipt(
            AgentToolActionControlledTaskPayload payload,
            Long taskRunId,
            String outcome,
            boolean preCheckPassed,
            String message,
            String errorCode,
            List<String> recommendedActions,
            TaskActorContext actorContext) {
        if (!properties.isControlledActionReceiptEnabled()) {
            return AgentToolActionControlledReceiptDelivery.skipped("受控工具动作 receipt 回写配置已关闭");
        }
        if (payload == null) {
            return AgentToolActionControlledReceiptDelivery.skipped("缺少已解析 payload，无法定位 agent-runtime session/run");
        }
        if (payload.sessionId() == null || payload.sessionId().isBlank()
                || payload.runId() == null || payload.runId().isBlank()) {
            return AgentToolActionControlledReceiptDelivery.skipped("缺少 sessionId 或 runId，无法写入 Agent timeline");
        }
        AgentToolActionControlledReceiptRequest request = request(
                payload, taskRunId, outcome, preCheckPassed, message, errorCode, recommendedActions, actorContext
        );
        try {
            PlatformApiResponse<AgentToolActionControlledReceiptResponse> response = restClientBuilder
                    .baseUrl(properties.getAgentRuntimeBaseUrl())
                    .build()
                    .post()
                    .uri("/internal/agent-runtime/sessions/{sessionId}/runs/{runId}/tool-executions/controlled-dry-run-receipts",
                            payload.sessionId(), payload.runId())
                    .headers(headers -> applyHeaders(headers, actorContext))
                    .body(request)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            return unwrap(response);
        } catch (RestClientException exception) {
            return handleFailure("回写 agent-runtime 受控工具动作 dry-run receipt 失败: " + exception.getMessage(), exception);
        } catch (RuntimeException exception) {
            return handleFailure("处理 agent-runtime 受控工具动作 dry-run receipt 响应失败: " + exception.getMessage(), exception);
        }
    }

    private AgentToolActionControlledReceiptRequest request(AgentToolActionControlledTaskPayload payload,
                                                            Long taskRunId,
                                                            String outcome,
                                                            boolean preCheckPassed,
                                                            String message,
                                                            String errorCode,
                                                            List<String> recommendedActions,
                                                            TaskActorContext actorContext) {
        return new AgentToolActionControlledReceiptRequest(
                payload.commandId(),
                payload.taskId(),
                taskRunId,
                properties.getExecutorId() + "-tool-action-controlled-dry-run",
                payload.tenantId(),
                payload.projectId(),
                actorContext == null ? null : actorContext.actorId(),
                payload.taskStatus(),
                outcome,
                preCheckPassed,
                false,
                message,
                errorCode,
                payload.auditId(),
                payload.toolCode(),
                payload.targetService(),
                payload.payloadReferenceType(),
                payload.payloadKey(),
                payload.hasPayloadStoreEvidence(),
                payload.payloadBodyAvailable(),
                payload.workerDispatchEnabled(),
                payload.policyVersions().size(),
                payload.delegationEvidence().size(),
                recommendedActions == null ? List.of() : recommendedActions,
                "agent-tool-action-controlled:" + payload.commandId() + ":" + outcome
        );
    }

    private void applyHeaders(HttpHeaders headers, TaskActorContext actorContext) {
        headers.set(PlatformContextHeaders.SOURCE_SERVICE, "task-management");
        headers.set(PlatformContextHeaders.ACTOR_ROLE, "SERVICE_ACCOUNT");
        headers.set(PlatformContextHeaders.ACTOR_TYPE, "SERVICE_ACCOUNT");
        if (actorContext != null && actorContext.traceId() != null && !actorContext.traceId().isBlank()) {
            headers.set(PlatformContextHeaders.TRACE_ID, actorContext.traceId().trim());
        }
    }

    private AgentToolActionControlledReceiptDelivery unwrap(
            PlatformApiResponse<AgentToolActionControlledReceiptResponse> response) {
        if (response == null) {
            throw new IllegalStateException("agent-runtime 返回空 receipt 响应");
        }
        if (response.getCode() == null || response.getCode() != 0) {
            throw new IllegalStateException("agent-runtime receipt 回写失败，code=" + response.getCode()
                    + ", reason=" + response.getReason() + ", message=" + response.getMessage());
        }
        AgentToolActionControlledReceiptResponse data = response.getData();
        if (data == null || !data.accepted()) {
            throw new IllegalStateException("agent-runtime 未接受受控工具动作 dry-run receipt");
        }
        return new AgentToolActionControlledReceiptDelivery(
                true,
                data.accepted(),
                data.duplicate(),
                data.identityKey(),
                data.eventType(),
                data.message()
        );
    }

    private AgentToolActionControlledReceiptDelivery handleFailure(String message, RuntimeException exception) {
        if (!properties.isControlledActionReceiptFailOpenOnError()) {
            throw exception;
        }
        log.warn("{}，当前配置为 fail-open，dry-run 状态机继续推进但 timeline 可能暂时缺少 receipt。", message);
        return AgentToolActionControlledReceiptDelivery.failedOpen(message);
    }
}
