/**
 * @Author : Cui
 * @Date: 2026/05/13 22:58
 * @Description DataSmart Govern Backend - AgentSessionController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller;

import com.czh.datasmart.govern.agent.controller.dto.AgentRunView;
import com.czh.datasmart.govern.agent.controller.dto.AgentSessionView;
import com.czh.datasmart.govern.agent.controller.dto.BindAgentToolRequest;
import com.czh.datasmart.govern.agent.controller.dto.CreateAgentSessionRequest;
import com.czh.datasmart.govern.agent.controller.dto.StartAgentRunRequest;
import com.czh.datasmart.govern.agent.service.AgentSessionService;
import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Agent 会话控制器。
 *
 * <p>该控制器是类 OpenClaw 智能体运行时的会话入口。
 * 模型接口 `/models/chat` 只解决“怎么调用模型”，而会话接口解决“Agent 在什么业务边界内工作、能调用哪些工具、运行状态如何追踪”。
 *
 * <p>路径设计：
 * 1. 内部路径使用 `/agent-runtime/sessions/**`，表达它属于 agent-runtime 微服务；
 * 2. 网关外部路径使用 `/api/agent/sessions/**`，前端和未来智能网关只需要认平台统一 API；
 * 3. Controller 同时挂载两组路径，便于本地绕过 gateway 调试，也便于通过 gateway 做权限验证。
 */
@RestController
@RequestMapping({"/agent-runtime/sessions", "/api/agent/sessions"})
@RequiredArgsConstructor
public class AgentSessionController {

    private final AgentSessionService agentSessionService;

    /**
     * 创建 Agent 会话。
     *
     * <p>业务含义：用户或上游智能网关声明一个治理目标，并把该目标绑定到租户、项目、工作空间和工具范围内。
     * 该接口不会执行模型推理，也不会立即调用工具；它只创建可治理的上下文容器。
     *
     * <p>权限建议：普通用户和项目负责人可以创建自己项目范围内的会话；
     * 租户管理员和运营人员可创建更大范围的排障会话；审计员通常只读。
     */
    @PostMapping
    public PlatformApiResponse<AgentSessionView> createSession(
            @Valid @RequestBody CreateAgentSessionRequest request,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success("Agent 会话创建成功", agentSessionService.createSession(request), traceId);
    }

    /**
     * 查询 Agent 会话列表。
     *
     * <p>当前支持租户、项目、操作者三类过滤。
     * 后续商业化后台应继续补状态、创建时间、渠道、工具类型、是否存在失败运行等过滤条件，并加分页。
     */
    @GetMapping
    public PlatformApiResponse<List<AgentSessionView>> listSessions(
            @RequestParam(value = "tenantId", required = false) Long tenantId,
            @RequestParam(value = "projectId", required = false) Long projectId,
            @RequestParam(value = "actorId", required = false) String actorId,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(agentSessionService.listSessions(tenantId, projectId, actorId), traceId);
    }

    /**
     * 查询 Agent 会话详情。
     *
     * <p>详情会返回工作空间、工具绑定和运行历史，适合前端恢复会话上下文、运营排障和审计复核。
     */
    @GetMapping("/{sessionId}")
    public PlatformApiResponse<AgentSessionView> getSession(
            @PathVariable("sessionId") String sessionId,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(agentSessionService.getSession(sessionId), traceId);
    }

    /**
     * 追加工具绑定。
     *
     * <p>该接口只把工具能力绑定到会话，不代表工具已经执行。
     * 真实工具执行仍需要后续 Agent Run 状态机、工具适配器、权限校验和审计事件共同完成。
     */
    @PostMapping("/{sessionId}/tools")
    public PlatformApiResponse<AgentSessionView> bindTool(
            @PathVariable("sessionId") String sessionId,
            @Valid @RequestBody BindAgentToolRequest request,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success("Agent 工具绑定成功", agentSessionService.bindTool(sessionId, request), traceId);
    }

    /**
     * 发起一次 Agent 运行。
     *
     * <p>当前返回 dry-run 运行记录，状态为 PLANNING 或 WAITING_HUMAN。
     * 未来真实版本会把该请求投递给 Agent 编排器，并通过 WebSocket/SSE/Kafka 事件持续回传运行进度。
     */
    @PostMapping("/{sessionId}/runs")
    public PlatformApiResponse<AgentRunView> startRun(
            @PathVariable("sessionId") String sessionId,
            @Valid @RequestBody StartAgentRunRequest request,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success("Agent Run 已创建", agentSessionService.startRun(sessionId, request, traceId), traceId);
    }

    /**
     * 取消一次 Agent 运行。
     *
     * <p>当前取消只更新内存状态。后续接入真实编排器后，需要额外发布取消事件，
     * 让 Python Runtime、工具执行器或任务中心停止下游工作，并把取消结果写回运行记录。
     */
    @PostMapping("/{sessionId}/runs/{runId}/cancel")
    public PlatformApiResponse<AgentRunView> cancelRun(
            @PathVariable("sessionId") String sessionId,
            @PathVariable("runId") String runId,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success("Agent Run 已取消", agentSessionService.cancelRun(sessionId, runId), traceId);
    }
}
