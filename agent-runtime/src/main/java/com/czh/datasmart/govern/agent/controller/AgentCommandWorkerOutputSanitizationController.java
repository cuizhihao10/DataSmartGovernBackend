/**
 * @Author : Cui
 * @Date: 2026/06/24 21:11
 * @Description DataSmart Govern Backend - AgentCommandWorkerOutputSanitizationController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller;

import com.czh.datasmart.govern.agent.controller.dto.AgentCommandWorkerOutputSanitizeRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentCommandWorkerOutputSanitizeResponse;
import com.czh.datasmart.govern.agent.service.runtime.AgentCommandWorkerOutputSanitizationService;
import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * command worker 输出片段净化内部控制器。
 *
 * <p>该控制器只面向服务间调用，用于真实 worker 或未来 sandbox runner 在写 artifact 前获取安全预览候选。
 * 它不是普通用户接口，也不是日志查询接口。生产环境必须继续叠加内网路由、服务账号签名、mTLS、网关 ACL
 * 或服务网格策略，避免外部调用方把任意文本送入净化链路探测策略。</p>
 *
 * <p>控制器本身不保存原始输出，不写 runtime event，也不返回下载凭据。所有净化细节由
 * {@link AgentCommandWorkerOutputSanitizationService} 统一处理，避免输出治理规则散落在 worker、artifact store、
 * receipt service 和 final-check service 中。</p>
 */
@RestController
@RequiredArgsConstructor
public class AgentCommandWorkerOutputSanitizationController {

    private final AgentCommandWorkerOutputSanitizationService outputSanitizationService;

    /**
     * 净化 command worker 的输出片段。
     *
     * <p>路由中携带 sessionId/runId，是为了让 worker 调用形态与 lease、receipt 保持一致，也便于未来把本请求与
     * artifactReference、taskRunId、traceId 串联审计。当前 service 仍以请求体中的低敏字段为主，不读取 projection
     * 或对象存储。</p>
     *
     * @param sessionId Agent 会话 ID。
     * @param runId Agent Run ID。
     * @param request worker 输出净化请求，允许短生命周期携带 rawOutputChunk。
     * @param traceId 跨服务 traceId，只进入统一响应，不影响净化策略。
     * @return 安全预览候选和低敏治理元数据；不会原样返回 rawOutputChunk。
     */
    @PostMapping({
            "/internal/agent-runtime/sessions/{sessionId}/runs/{runId}/tool-executions/command-worker-output-sanitizations",
            "/api/internal/agent-runtime/sessions/{sessionId}/runs/{runId}/tool-executions/command-worker-output-sanitizations"
    })
    public PlatformApiResponse<AgentCommandWorkerOutputSanitizeResponse> sanitizeCommandWorkerOutput(
            @PathVariable String sessionId,
            @PathVariable String runId,
            @RequestBody AgentCommandWorkerOutputSanitizeRequest request,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        AgentCommandWorkerOutputSanitizeResponse response =
                outputSanitizationService.sanitize(normalizePathContext(request, sessionId, runId));
        return PlatformApiResponse.success(response, traceId);
    }

    /**
     * 将路由中的 sessionId/runId 回填到请求体缺省值。
     *
     * <p>内部 worker 可能已经在请求体里携带 run/session；如果未携带，则使用 path 里的值，保证 service 侧能统一看到
     * 低敏上下文。这里不允许扩大权限，也不做数据范围判断，真正授权仍应由 worker lease、receipt 和 artifact gate
     * 分阶段完成。</p>
     */
    private AgentCommandWorkerOutputSanitizeRequest normalizePathContext(AgentCommandWorkerOutputSanitizeRequest request,
                                                                         String sessionId,
                                                                         String runId) {
        if (request == null) {
            return null;
        }
        return new AgentCommandWorkerOutputSanitizeRequest(
                request.commandId(),
                request.outputChannel(),
                request.rawOutputChunk(),
                request.rawOutputEncoding(),
                request.rawOutputTruncatedByWorker(),
                request.workerOutputByteLimitBytes(),
                request.requestedPreviewBytes(),
                request.tenantId(),
                request.projectId(),
                request.actorId(),
                request.runId() == null || request.runId().isBlank() ? runId : request.runId(),
                request.sessionId() == null || request.sessionId().isBlank() ? sessionId : request.sessionId(),
                request.toolCode(),
                request.requesterComponent()
        );
    }
}
