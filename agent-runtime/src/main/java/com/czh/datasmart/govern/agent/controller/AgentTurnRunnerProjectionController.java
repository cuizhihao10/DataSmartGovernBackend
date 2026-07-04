/**
 * @Author : Cui
 * @Date: 2026/07/02 18:08
 * @Description DataSmart Govern Backend - AgentTurnRunnerProjectionController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller;

import com.czh.datasmart.govern.agent.controller.dto.AgentTurnRunnerProjectionQueryResponse;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventProjectionQuery;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventQueryAccessContext;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventQueryAccessContextResolver;
import com.czh.datasmart.govern.agent.service.runtime.AgentTurnRunnerProjectionService;
import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 受控多 Agent Turn Runner 投影查询控制器。
 *
 * <p>该控制器与执行会话投影并列，仍然挂在 runtime-events 路径下。原因是当前数据来源仍是 Python Runtime
 * 发布的 runtime event，而不是 Java 主动创建的执行任务。这样命名可以提醒调用方：这里读的是控制面事实，
 * 不是“立即执行子 Agent”的命令接口。</p>
 *
 * <p>路径含义：</p>
 * <p>1. `/agent-runtime/runtime-events/agent-turn-runner-snapshots`：服务内部直连调试路径；</p>
 * <p>2. `/api/agent/runtime-events/agent-turn-runner-snapshots`：经 gateway 暴露给管理台或审计台的统一路径。</p>
 *
 * <p>安全边界：该接口固定查询 `agent_turn_runner_recorded` 事件，所有数据范围仍由
 * `AgentRuntimeEventProjectionAccessSupport` 收口，不返回 prompt、SQL、工具参数、样本数据、模型输出、
 * token、内部 endpoint、payloadReference 正文或 commandId。checkpointId 只在
 * `turnRunnerCheckpoint` 子视图中以低敏 LangGraph 恢复 locator 形式返回，用于 pause/resume/fork/recover
 * 定位，不代表暴露 checkpoint state 正文。</p>
 */
@RestController
@RequestMapping({"/agent-runtime/runtime-events", "/api/agent/runtime-events"})
@RequiredArgsConstructor
public class AgentTurnRunnerProjectionController {

    private final AgentTurnRunnerProjectionService turnRunnerProjectionService;
    private final AgentRuntimeEventQueryAccessContextResolver accessContextResolver;

    /**
     * 查询受控多 Agent Turn Runner runtime event 的强类型投影视图。
     *
     * <p>典型使用场景：</p>
     * <p>1. Agent 运行详情页展示某轮 turn runner 是否等待审批、等待 worker receipt 或等待 Java outbox；</p>
     * <p>2. 审计员验证 manager-as-tools 仍是“调度描述”，没有被 Python Runtime 误执行成真实工具调用；</p>
     * <p>3. 后续真实多 Agent runner 在恢复前读取 requiredEvidenceCodes，判断哪些 host fact 尚未补齐。</p>
     */
    @GetMapping("/agent-turn-runner-snapshots")
    public PlatformApiResponse<AgentTurnRunnerProjectionQueryResponse> queryAgentTurnRunnerSnapshots(
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestParam(value = "projectId", required = false) String projectId,
            @RequestParam(value = "actorId", required = false) String actorId,
            @RequestParam(value = "requestId", required = false) String requestId,
            @RequestParam(value = "runId", required = false) String runId,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "severity", required = false) String severity,
            @RequestParam(value = "afterSequence", required = false) Long afterSequence,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) String currentTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) String currentActorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String currentActorRole,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        AgentRuntimeEventProjectionQuery query = new AgentRuntimeEventProjectionQuery(
                tenantId,
                projectId,
                actorId,
                requestId,
                runId,
                sessionId,
                null,
                severity,
                limit,
                afterSequence
        );
        AgentRuntimeEventQueryAccessContext accessContext = accessContextResolver.resolve(
                currentTenantId,
                currentActorId,
                currentActorRole,
                traceId,
                dataScopeLevel,
                authorizedProjectIds
        );
        return PlatformApiResponse.success(
                turnRunnerProjectionService.querySnapshots(query, accessContext),
                traceId
        );
    }
}
