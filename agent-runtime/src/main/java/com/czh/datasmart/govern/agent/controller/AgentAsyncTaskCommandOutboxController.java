/**
 * @Author : Cui
 * @Date: 2026/05/31 17:20
 * @Description DataSmart Govern Backend - AgentAsyncTaskCommandOutboxController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller;

import com.czh.datasmart.govern.agent.controller.dto.AgentAsyncTaskCommandOutboxDispatchResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentAsyncTaskCommandOutboxOperationRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentAsyncTaskCommandOutboxOperationResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentAsyncTaskCommandOutboxQueryResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentAsyncTaskCommandObservationView;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunAsyncTaskCommandOutboxEnqueueResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolDagSelectedNodeOutboxEnqueueRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolDagSelectedNodeOutboxEnqueueResponse;
import com.czh.datasmart.govern.agent.event.command.AgentAsyncTaskCommandOutboxDiagnostics;
import com.czh.datasmart.govern.agent.event.command.AgentAsyncTaskCommandOutboxDispatcher;
import com.czh.datasmart.govern.agent.event.command.AgentAsyncTaskCommandOutboxStore;
import com.czh.datasmart.govern.agent.service.AgentSessionService;
import com.czh.datasmart.govern.agent.service.execution.AgentAsyncTaskCommandOutboxOperationService;
import com.czh.datasmart.govern.agent.service.execution.AgentRunAsyncTaskCommandOutboxService;
import com.czh.datasmart.govern.agent.service.execution.AgentRunToolDagSelectedNodeOutboxService;
import com.czh.datasmart.govern.agent.service.session.AgentSessionAccessContext;
import com.czh.datasmart.govern.agent.service.session.AgentSessionEndpointAccessResolver;
import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final AgentRunToolDagSelectedNodeOutboxService selectedNodeOutboxService;
    private final AgentSessionService sessionService;
    private final AgentSessionEndpointAccessResolver endpointAccessResolver;
    private final AgentAsyncTaskCommandOutboxStore outboxStore;
    private final AgentAsyncTaskCommandOutboxOperationService operationService;
    private final AgentAsyncTaskCommandOutboxDispatcher dispatcher;

    /**
     * 将某次 Run 中可下发的异步命令写入 outbox。
     */
    @PostMapping("/sessions/{sessionId}/runs/{runId}/tool-executions/async-command-outbox/enqueue")
    public PlatformApiResponse<AgentRunAsyncTaskCommandOutboxEnqueueResponse> enqueueRunCommands(
            @PathVariable("sessionId") String sessionId,
            @PathVariable("runId") String runId,
            @RequestHeader HttpHeaders headers,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        requireAutomatedMutationAccess(sessionId, headers);
        return PlatformApiResponse.success(outboxService.enqueueRunAsyncTaskCommands(sessionId, runId), traceId);
    }

    /**
     * 把已经经过 DAG dry-run 展示与显式确认的异步节点写入 outbox。
     *
     * <p>该路由是面向智能网关动作审批面板和 Python Agent loop 的推荐入口。相比兼容保留的 Run 级 enqueue，
     * 它要求调用方明确传入 nodeIds/auditIds、上一次 dry-run 的选择指纹以及 {@code confirmed=true}。
     * 服务端会重新执行 dry-run 并做整批校验，只允许仍处于异步 outbox 候选状态的节点入箱。</p>
     *
     * <p>安全边界：请求体没有 targetEndpoint、topic 和工具参数字段。真实跨服务路由只能由 Java 控制面
     * 根据 auditId 重新读取，不能由模型或前端覆盖。</p>
     */
    @PostMapping("/sessions/{sessionId}/runs/{runId}/tool-executions/dag-selected-node-outbox/enqueue")
    public PlatformApiResponse<AgentRunToolDagSelectedNodeOutboxEnqueueResponse> enqueueSelectedDagNodes(
            @PathVariable("sessionId") String sessionId,
            @PathVariable("runId") String runId,
            @RequestBody AgentRunToolDagSelectedNodeOutboxEnqueueRequest request,
            @RequestHeader HttpHeaders headers,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        requireAutomatedMutationAccess(sessionId, headers);
        return PlatformApiResponse.success(
                selectedNodeOutboxService.enqueueSelectedAsyncNodes(sessionId, runId, request, traceId),
                traceId
        );
    }

    /**
     * 查询异步命令 outbox 记录。
     */
    @GetMapping("/async-task-commands/outbox")
    public PlatformApiResponse<AgentAsyncTaskCommandOutboxQueryResponse> query(
            @RequestParam(value = "sessionId") String sessionId,
            @RequestParam(value = "runId", required = false) String runId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestHeader HttpHeaders headers,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        requireReadAccess(sessionId, headers);
        return PlatformApiResponse.success(
                outboxService.queryForSession(sessionId, runId, status, limit), traceId);
    }

    /**
     * 为统一生命周期图精确读取一条命令 outbox 事实。
     *
     * <p>该路由先按 session 恢复原用户边界并执行对象级读取校验，再按完整三元组查询；响应不会暴露通用
     * outbox 诊断接口中的 endpoint、payloadReference 或 lastError。</p>
     */
    @GetMapping("/sessions/{sessionId}/runs/{runId}/async-task-commands/{commandId}/observation")
    public PlatformApiResponse<AgentAsyncTaskCommandObservationView> observe(
            @PathVariable("sessionId") String sessionId,
            @PathVariable("runId") String runId,
            @PathVariable("commandId") String commandId,
            @RequestHeader HttpHeaders headers,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        requireReadAccess(sessionId, headers);
        return PlatformApiResponse.success(outboxService.observe(sessionId, runId, commandId), traceId);
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

    /**
     * 将失败、阻断或死信命令重新入队。
     *
     * <p>该接口面向平台管理员/运维人员。典型使用场景是：task-management topic ACL 已修复、目标服务恢复、
     * payload 契约修正，管理员希望让 dispatcher 再次尝试投递。服务层会保留 attemptCount，
     * 并只允许 FAILED/BLOCKED/DEAD_LETTER 进入 PENDING，避免误重放正在投递或已经成功的命令。</p>
     */
    @PostMapping("/async-task-commands/outbox/{outboxId}/requeue")
    public PlatformApiResponse<AgentAsyncTaskCommandOutboxOperationResponse> requeue(
            @PathVariable("outboxId") String outboxId,
            @RequestBody(required = false) AgentAsyncTaskCommandOutboxOperationRequest request,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) String actorId,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(
                "Agent 异步命令 outbox 已重新入队，等待 dispatcher 补偿投递",
                operationService.requeue(outboxId, request, actorId),
                traceId
        );
    }

    /**
     * 将失败或阻断命令转入死信。
     *
     * <p>DEAD_LETTER 是自动恢复与人工治理之间的边界：进入死信后 dispatcher 不再自动领取，
     * 管理员可以在补偿台继续排查、追加备注、重新入队或忽略。这样可以避免坏命令在队列里反复热循环。</p>
     */
    @PostMapping("/async-task-commands/outbox/{outboxId}/dead-letter")
    public PlatformApiResponse<AgentAsyncTaskCommandOutboxOperationResponse> deadLetter(
            @PathVariable("outboxId") String outboxId,
            @RequestBody(required = false) AgentAsyncTaskCommandOutboxOperationRequest request,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) String actorId,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(
                "Agent 异步命令 outbox 已进入 dead-letter，停止自动重试",
                operationService.deadLetter(outboxId, request, actorId),
                traceId
        );
    }

    /**
     * 人工忽略失败、阻断或死信命令。
     */
    @PostMapping("/async-task-commands/outbox/{outboxId}/ignore")
    public PlatformApiResponse<AgentAsyncTaskCommandOutboxOperationResponse> ignore(
            @PathVariable("outboxId") String outboxId,
            @RequestBody(required = false) AgentAsyncTaskCommandOutboxOperationRequest request,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) String actorId,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(
                "Agent 异步命令 outbox 已人工忽略并归档",
                operationService.ignore(outboxId, request, actorId),
                traceId
        );
    }

    /**
     * 追加人工处理备注。
     */
    @PostMapping("/async-task-commands/outbox/{outboxId}/notes")
    public PlatformApiResponse<AgentAsyncTaskCommandOutboxOperationResponse> appendNote(
            @PathVariable("outboxId") String outboxId,
            @RequestBody(required = false) AgentAsyncTaskCommandOutboxOperationRequest request,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) String actorId,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(
                "Agent 异步命令 outbox 已追加人工处理备注",
                operationService.appendNote(outboxId, request, actorId),
                traceId
        );
    }

    /**
     * 入箱会推进异步工具的真实副作用，因此不能只相信 URL 中的 sessionId。
     * 浏览器调用必须是原会话发起人；Python Runtime 只有同时命中服务白名单和共享凭证时，
     * 才能从持久化会话恢复原用户边界继续执行，避免服务账号绕过用户权限。
     */
    private void requireAutomatedMutationAccess(String sessionId, HttpHeaders headers) {
        AgentSessionAccessContext requestAccess = new AgentSessionAccessContext(
                longHeader(headers, PlatformContextHeaders.TENANT_ID),
                longHeader(headers, PlatformContextHeaders.PROJECT_ID),
                headers.getFirst(PlatformContextHeaders.ACTOR_ID),
                headers.getFirst(PlatformContextHeaders.ACTOR_ROLE)
        );
        sessionService.requireMutationAccess(sessionId, endpointAccessResolver.resolveAutomatedExecutionAccess(
                sessionId,
                requestAccess,
                headers.getFirst(PlatformContextHeaders.SOURCE_SERVICE),
                headers.getFirst(PlatformContextHeaders.INTERNAL_SERVICE_TOKEN)
        ));
    }

    /** 读取单条命令前恢复可信内部服务对应的会话所有者，并复用现有会话对象级授权。 */
    private void requireReadAccess(String sessionId, HttpHeaders headers) {
        AgentSessionAccessContext requestAccess = new AgentSessionAccessContext(
                longHeader(headers, PlatformContextHeaders.TENANT_ID),
                longHeader(headers, PlatformContextHeaders.PROJECT_ID),
                headers.getFirst(PlatformContextHeaders.ACTOR_ID),
                headers.getFirst(PlatformContextHeaders.ACTOR_ROLE)
        );
        sessionService.getSession(sessionId, endpointAccessResolver.resolveReadAccess(
                sessionId,
                requestAccess,
                headers.getFirst(PlatformContextHeaders.SOURCE_SERVICE),
                headers.getFirst(PlatformContextHeaders.INTERNAL_SERVICE_TOKEN)
        ));
    }

    /**
     * 安全解析由 Gateway 注入的数字范围 Header。
     *
     * <p>缺失或格式非法时返回 null，后续可信上下文校验会 fail-closed 拒绝；这里不能把非法值默认为 0，
     * 否则可能意外落入平台级或默认租户范围。</p>
     */
    private Long longHeader(HttpHeaders headers, String name) {
        String value = headers.getFirst(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
