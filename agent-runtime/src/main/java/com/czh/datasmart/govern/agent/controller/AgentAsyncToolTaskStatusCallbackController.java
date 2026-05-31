/**
 * @Author : Cui
 * @Date: 2026/05/31 23:58
 * @Description DataSmart Govern Backend - AgentAsyncToolTaskStatusCallbackController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller;

import com.czh.datasmart.govern.agent.controller.dto.AgentAsyncToolTaskStatusCallbackRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentAsyncToolTaskStatusCallbackResponse;
import com.czh.datasmart.govern.agent.service.AgentAsyncToolTaskStatusCallbackService;
import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 异步工具任务状态内部回调控制器。
 *
 * <p>路由放在 `/internal/agent-runtime/**` 下，明确它是微服务之间的控制面接口。
 * task-management worker 在认领、执行成功、执行失败或临时退避时调用该接口，agent-runtime 再复用已有工具审计事件发布机制，
 * 让 Python Runtime、前端事件投影和审计台看到同一条状态事实。</p>
 *
 * <p>生产化安全要求：
 * 当前代码先固定业务契约和学习注释，后续必须在 gateway、服务网格或安全中间件层增加服务账号鉴权、签名、mTLS 或内网白名单。
 * 否则任何能访问内部网段的调用方都可能伪造工具成功/失败状态，影响 Agent 决策和审计可信度。</p>
 */
@RestController
@RequestMapping("/internal/agent-runtime")
@RequiredArgsConstructor
public class AgentAsyncToolTaskStatusCallbackController {

    private final AgentAsyncToolTaskStatusCallbackService callbackService;

    /**
     * 接收 task-management worker 的异步工具任务状态回调。
     *
     * @param sessionId Agent 会话 ID。
     * @param runId Agent Run ID。
     * @param auditId 工具审计 ID。
     * @param request worker 回调载荷。
     * @param traceId 链路追踪 ID，会透传到统一响应，方便跨服务日志排查。
     * @return 回调处理结果和当前审计状态。
     */
    @PostMapping("/sessions/{sessionId}/runs/{runId}/tool-executions/{auditId}/async-task-status")
    public PlatformApiResponse<AgentAsyncToolTaskStatusCallbackResponse> callbackAsyncTaskStatus(
            @PathVariable("sessionId") String sessionId,
            @PathVariable("runId") String runId,
            @PathVariable("auditId") String auditId,
            @RequestBody AgentAsyncToolTaskStatusCallbackRequest request,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(callbackService.applyStatusCallback(sessionId, runId, auditId, request), traceId);
    }
}
