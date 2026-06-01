/**
 * @Author : Cui
 * @Date: 2026/06/01 22:22
 * @Description DataSmart Govern Backend - AgentRunToolDagConfirmationController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller;

import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolDagConfirmationQueryResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolDagConfirmationView;
import com.czh.datasmart.govern.agent.service.execution.confirmation.AgentRunToolDagConfirmationQueryService;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventQueryAccessContext;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventQueryAccessContextResolver;
import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * DAG selected-node 确认记录审计查询控制器。
 *
 * <p>该控制器把 selected-node confirmation 从“内部执行副产物”升级为“可被审计读取的业务证据”。
 * 这与现代 Agent 工程里 durable action、human-in-the-loop、工具调用可见性和恢复审计的方向一致：
 * 模型建议动作、用户或策略确认动作、控制面持久化确认事实、worker 再执行动作。</p>
 *
 * <p>权限边界：gateway 应把本路由映射为 {@code AI_RUNTIME + VIEW_TOOL_CONFIRMATIONS}。
 * 入口授权通过后，服务层仍会根据 Header 中的数据范围做二次收口，防止项目负责人、普通用户或服务账号读取越权确认记录。</p>
 */
@RestController
@RequestMapping({"/agent-runtime/sessions", "/api/agent/sessions"})
@RequiredArgsConstructor
public class AgentRunToolDagConfirmationController {

    private final AgentRunToolDagConfirmationQueryService queryService;
    private final AgentRuntimeEventQueryAccessContextResolver accessContextResolver;

    /**
     * 查询某个 Agent Run 的 DAG selected-node 确认历史。
     *
     * <p>路由含义：
     * - {@code sessionId}：限定会话上下文，避免只凭 runId 读取跨会话确认记录；
     * - {@code runId}：限定一次 Agent 规划/执行尝试；
     * - {@code dag-confirmations}：强调返回的是确认事实，不是工具执行结果，也不是 outbox 状态机。</p>
     *
     * <p>返回行为：
     * - 只返回当前访问主体数据范围内的记录；
     * - 默认最多 50 条，最大 200 条，避免列表接口退化成审计导出；
     * - 不返回工具参数、SQL、prompt、样本数据或原始 payload。</p>
     */
    @GetMapping("/{sessionId}/runs/{runId}/tool-executions/dag-confirmations")
    public PlatformApiResponse<AgentRunToolDagConfirmationQueryResponse> listRunConfirmations(
            @PathVariable("sessionId") String sessionId,
            @PathVariable("runId") String runId,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) String currentTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) String currentActorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String currentActorRole,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        AgentRuntimeEventQueryAccessContext accessContext = accessContextResolver.resolve(
                currentTenantId,
                currentActorId,
                currentActorRole,
                traceId,
                dataScopeLevel,
                authorizedProjectIds
        );
        return PlatformApiResponse.success(queryService.listByRun(sessionId, runId, limit, accessContext), traceId);
    }

    /**
     * 按 confirmationId 查询单条确认记录。
     *
     * <p>详情接口适合从 runtime event、outbox、审批卡片或管理员补偿台跳转。
     * 服务层会强制校验 confirmationId 归属的 sessionId/runId 与 URL 一致，避免调用方拿到一个确认 ID 后跨上下文读取证据。</p>
     */
    @GetMapping("/{sessionId}/runs/{runId}/tool-executions/dag-confirmations/{confirmationId}")
    public PlatformApiResponse<AgentRunToolDagConfirmationView> getRunConfirmation(
            @PathVariable("sessionId") String sessionId,
            @PathVariable("runId") String runId,
            @PathVariable("confirmationId") String confirmationId,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) String currentTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) String currentActorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String currentActorRole,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        AgentRuntimeEventQueryAccessContext accessContext = accessContextResolver.resolve(
                currentTenantId,
                currentActorId,
                currentActorRole,
                traceId,
                dataScopeLevel,
                authorizedProjectIds
        );
        return PlatformApiResponse.success(
                queryService.getByConfirmationId(sessionId, runId, confirmationId, accessContext),
                traceId
        );
    }
}
