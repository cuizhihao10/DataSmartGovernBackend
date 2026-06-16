/**
 * @Author : Cui
 * @Date: 2026/06/16 00:00
 * @Description DataSmart Govern Backend - HttpAgentToolActionApprovalFactEvaluator.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.config.AgentToolActionResumeFactBundleProperties;
import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;

/**
 * 基于 HTTP 的 Agent 工具动作审批事实评估客户端。
 *
 * <p>该实现调用 permission-admin 的 `/permissions/agent/tool-action-approvals/evaluate` 能力，
 * 用于确认 Python Runtime 或其他 Agent Host 提交的 approvalFactId 是否确实是服务端登记、未过期、
 * 已批准、并绑定当前 tenant/project/actor/session/run/command/tool 的事实。</p>
 *
 * <p>安全边界：
 * 1. 仅发送白名单定位字段，不发送工具参数、payload body、prompt、SQL 或模型输出；
 * 2. 远端不可用时按配置 fail-closed，不会把“没查到”误判为“已批准”；
 * 3. 捕获异常后只返回低敏 issueCode，避免把内网 URL、Header、token、响应正文扩散到 API 响应。</p>
 */
@Component
@RequiredArgsConstructor
public class HttpAgentToolActionApprovalFactEvaluator implements AgentToolActionApprovalFactEvaluator {

    private final AgentToolActionResumeFactBundleProperties properties;
    private final RestClient.Builder restClientBuilder;

    /**
     * 调用 permission-admin 评估审批事实。
     *
     * <p>如果配置关闭远程评估，方法会返回 NOT_EVALUATED，而不是抛异常。
     * 这样本地开发可以继续查询 outbox/receipt 事实，同时清楚看到审批事实还没有真实接入。</p>
     */
    @Override
    public AgentToolActionApprovalFactRemoteResult evaluate(AgentToolActionApprovalFactRemoteRequest request) {
        if (!Boolean.TRUE.equals(properties.getApprovalFactEvaluationEnabled())) {
            return AgentToolActionApprovalFactRemoteResult.notEvaluated(
                    "APPROVAL_FACT_REMOTE_EVALUATION_DISABLED"
            );
        }
        try {
            PlatformApiResponse<AgentToolActionApprovalFactRemoteResult> response = restClientBuilder
                    .requestFactory(requestFactory())
                    .build()
                    .post()
                    .uri(properties.getApprovalFactEvaluateUrl())
                    .header(PlatformContextHeaders.TRACE_ID, safeText(request.traceId()))
                    .header(PlatformContextHeaders.SOURCE_SERVICE, "agent-runtime")
                    .body(toPayload(request))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            return unwrap(response);
        } catch (RestClientException | IllegalStateException ex) {
            /*
             * 不把 ex.getMessage() 写入返回值。
             * RestClientException 经常包含 URL、HTTP 状态、响应片段，甚至可能带出内部网关路径。
             * 对外只保留 issueCode；详细异常应由受控日志系统承接。
             */
            boolean retryable = !Boolean.TRUE.equals(properties.getFailClosedWhenApprovalRemoteUnavailable());
            return AgentToolActionApprovalFactRemoteResult.remoteUnavailable(retryable);
        }
    }

    private SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        long timeoutMs = properties.getApprovalFactTimeoutMs() == null
                ? 1500L
                : Math.max(1L, properties.getApprovalFactTimeoutMs());
        Duration timeout = Duration.ofMillis(timeoutMs);
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        return requestFactory;
    }

    private ApprovalFactEvaluatePayload toPayload(AgentToolActionApprovalFactRemoteRequest request) {
        return new ApprovalFactEvaluatePayload(
                request.approvalFactId(),
                request.tenantId(),
                request.projectId(),
                request.actorId(),
                request.sessionId(),
                request.runId(),
                request.commandId(),
                request.toolCode(),
                request.requestedPolicyVersion()
        );
    }

    private AgentToolActionApprovalFactRemoteResult unwrap(
            PlatformApiResponse<AgentToolActionApprovalFactRemoteResult> response) {
        if (response == null) {
            throw new IllegalStateException("permission-admin 返回空审批事实评估响应");
        }
        if (response.getCode() == null || response.getCode() != 0) {
            throw new IllegalStateException("permission-admin 审批事实评估失败");
        }
        if (response.getData() == null) {
            throw new IllegalStateException("permission-admin 审批事实评估成功但 data 为空");
        }
        return response.getData();
    }

    private String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * 与 permission-admin HTTP DTO 对齐的本地请求体。
     *
     * <p>这里没有 traceId 字段，因为 traceId 通过标准 Header 透传；
     * 请求体只保留事实验真所需的业务定位字段。</p>
     */
    private record ApprovalFactEvaluatePayload(String approvalFactId,
                                               Long tenantId,
                                               Long projectId,
                                               String actorId,
                                               String sessionId,
                                               String runId,
                                               String commandId,
                                               String toolCode,
                                               String requestedPolicyVersion) {
    }
}
