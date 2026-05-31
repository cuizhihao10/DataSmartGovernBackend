/**
 * @Author : Cui
 * @Date: 2026/05/31 17:20
 * @Description DataSmart Govern Backend - AgentAsyncTaskCommandOutboxController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller;

import com.czh.datasmart.govern.agent.controller.dto.AgentAsyncTaskCommandOutboxDispatchResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentAsyncTaskCommandOutboxQueryResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunAsyncTaskCommandOutboxEnqueueResponse;
import com.czh.datasmart.govern.agent.event.command.AgentAsyncTaskCommandOutboxDiagnostics;
import com.czh.datasmart.govern.agent.event.command.AgentAsyncTaskCommandOutboxDispatcher;
import com.czh.datasmart.govern.agent.event.command.AgentAsyncTaskCommandOutboxStore;
import com.czh.datasmart.govern.agent.service.execution.AgentRunAsyncTaskCommandOutboxService;
import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 异步命令 outbox 控制器。
 *
 * <p>该控制器让 ASYNC_TASK 从“只读命令草案”进入“可恢复投递记录”阶段。
 * 它不会直接执行工具，也不会直接创建 task-management 任务；它只提供入箱、查询、诊断和手动投递入口。</p>
 */
@RestController
@RequestMapping({
        "/agent-runtime",
        "/api/agent"
})
@RequiredArgsConstructor
public class AgentAsyncTaskCommandOutboxController {

    private final AgentRunAsyncTaskCommandOutboxService outboxService;
    private final AgentAsyncTaskCommandOutboxStore outboxStore;
    private final AgentAsyncTaskCommandOutboxDispatcher dispatcher;

    /**
     * 将某次 Run 中可下发的异步命令写入 outbox。
     */
    @PostMapping("/sessions/{sessionId}/runs/{runId}/tool-executions/async-command-outbox/enqueue")
    public PlatformApiResponse<AgentRunAsyncTaskCommandOutboxEnqueueResponse> enqueueRunCommands(
            @PathVariable("sessionId") String sessionId,
            @PathVariable("runId") String runId,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(outboxService.enqueueRunAsyncTaskCommands(sessionId, runId), traceId);
    }

    /**
     * 查询异步命令 outbox 记录。
     */
    @GetMapping("/async-task-commands/outbox")
    public PlatformApiResponse<AgentAsyncTaskCommandOutboxQueryResponse> query(
            @RequestParam(value = "runId", required = false) String runId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(outboxService.query(runId, status, limit), traceId);
    }

    /**
     * 查询异步命令 outbox 诊断摘要。
     */
    @GetMapping("/async-task-commands/outbox/diagnostics")
    public PlatformApiResponse<AgentAsyncTaskCommandOutboxDiagnostics> diagnostics(
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(outboxStore.diagnostics(), traceId);
    }

    /**
     * 手动执行一轮异步命令 outbox 投递。
     *
     * <p>该入口适合本地联调、运维补偿和灰度阶段。生产环境开启后台 dispatcher 后，仍可保留该入口用于人工补偿。</p>
     */
    @PostMapping("/async-task-commands/outbox/dispatch-once")
    public PlatformApiResponse<AgentAsyncTaskCommandOutboxDispatchResponse> dispatchOnce(
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(
                AgentAsyncTaskCommandOutboxDispatchResponse.from(dispatcher.dispatchOnce()),
                traceId
        );
    }
}
