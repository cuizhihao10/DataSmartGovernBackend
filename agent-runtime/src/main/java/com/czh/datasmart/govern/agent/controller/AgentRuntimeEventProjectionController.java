/**
 * @Author : Cui
 * @Date: 2026/05/27 00:00
 * @Description DataSmart Govern Backend - AgentRuntimeEventProjectionController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller;

import com.czh.datasmart.govern.agent.controller.dto.AgentRuntimeEventConsumerDiagnosticsView;
import com.czh.datasmart.govern.agent.controller.dto.AgentRuntimeEventProjectionQueryResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentRuntimeEventReplayAckRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentRuntimeEventReplayCursorView;
import com.czh.datasmart.govern.agent.controller.dto.AgentRuntimeEventReplayResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentModelGatewayRoutingProjectionQueryResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentSessionHandoffDagQueryResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentSessionSchedulingProjectionQueryResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentSkillVisibilitySnapshotIndexDiagnosticsView;
import com.czh.datasmart.govern.agent.controller.dto.AgentSkillVisibilitySnapshotProjectionQueryResponse;
import com.czh.datasmart.govern.agent.service.runtime.AgentModelGatewayRoutingProjectionService;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventQueryAccessContext;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventQueryAccessContextResolver;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventProjectionQuery;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventProjectionQueryService;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventReplayService;
import com.czh.datasmart.govern.agent.service.runtime.AgentSessionHandoffDagService;
import com.czh.datasmart.govern.agent.service.runtime.AgentSessionSchedulingProjectionService;
import com.czh.datasmart.govern.agent.service.runtime.AgentSkillVisibilitySnapshotProjectionService;
import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent runtime event 投影查询控制器。
 *
 * <p>该控制器是“Agent 运行事件中心”的最小 API 雏形。
 * 它查询的是 Java agent-runtime 已经从 Kafka 消费并投影到控制面的事件，不直接访问 Kafka，也不直接访问 Python Runtime。
 * 这样做的意义是：即使 Python Runtime 或 Kafka 短暂不可用，Java 控制面仍能展示自己已经接收到的热窗口事件。</p>
 *
 * <p>权限建议：
 * - 普通用户只能查询自己 actorId 范围内的事件；
 * - 项目 owner 可以查询项目范围；
 * - 平台运维可以查询诊断信息；
 * - 审计员可以查询高风险/approval/audit 事件。
 * 当前第一版先暴露服务内 API 契约，后续应由 gateway + permission-admin 加上 `AI_RUNTIME/VIEW_EVENTS`
 * 和 `AI_RUNTIME/DIAGNOSE` 之类的权限语义。</p>
 */
@RestController
@RequestMapping({"/agent-runtime/runtime-events", "/api/agent/runtime-events"})
@RequiredArgsConstructor
public class AgentRuntimeEventProjectionController {

    private final AgentRuntimeEventProjectionQueryService queryService;
    private final AgentRuntimeEventReplayService replayService;
    private final AgentSkillVisibilitySnapshotProjectionService skillVisibilitySnapshotProjectionService;
    private final AgentSessionSchedulingProjectionService agentSessionSchedulingProjectionService;
    private final AgentSessionHandoffDagService agentSessionHandoffDagService;
    private final AgentModelGatewayRoutingProjectionService agentModelGatewayRoutingProjectionService;
    private final AgentRuntimeEventQueryAccessContextResolver accessContextResolver;

    /**
     * 查询 Java 控制面已消费的 Agent runtime event 投影。
     *
     * <p>查询参数说明：
     * - `runId`：运行详情页最常用；
     * - `sessionId`：排查 WebSocket/会话重连问题；
     * - `requestId`：从 gateway 或 HTTP 日志反查；
     * - `tenantId/projectId/actorId`：多租户、项目和操作者范围过滤；
     * - `eventType/severity`：按事件类型和严重级别过滤；
     * - `afterSequence`：断线续传或外部 replay source 已确认的 Java 控制面游标，只返回更大的 replaySequence；
     * - `limit`：单次返回上限，默认 100，最大 1000。</p>
     */
    @GetMapping
    public PlatformApiResponse<AgentRuntimeEventProjectionQueryResponse> queryEvents(
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestParam(value = "projectId", required = false) String projectId,
            @RequestParam(value = "actorId", required = false) String actorId,
            @RequestParam(value = "requestId", required = false) String requestId,
            @RequestParam(value = "runId", required = false) String runId,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "eventType", required = false) String eventType,
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
                eventType,
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
        return PlatformApiResponse.success(queryService.query(query, accessContext), traceId);
    }

    /**
     * 查询 Skill 可见性快照 runtime event 的强类型投影视图。
     *
     * <p>该接口服务“当前会话到底看见哪些 Skill”的治理场景。它不是第二个通用事件查询入口，而是固定读取
     * `skill_visibility_snapshot_recorded` 事件，并把 attributes 解析成低敏强类型 DTO。</p>
     *
     * <p>产品用途示例：</p>
     * <p>1. 前端 Agent 运行详情页展示本轮可见 Skill、隐藏 Skill 数量和隐藏原因分布；</p>
     * <p>2. 运维台排查“为什么某个租户没有看到某项能力”；</p>
     * <p>3. 后续 Skill Marketplace 统计能力命中、隐藏、权限缺口和策略漂移；</p>
     * <p>4. Java replay index 把 Python 会话能力事实纳入控制面统一查询。</p>
     *
     * <p>安全边界：该接口仍会解析 gateway 注入的租户、操作者、数据范围和授权项目 Header，并由服务层收口查询。
     * 返回字段只包含低敏聚合摘要，不返回 prompt、SQL、工具参数、完整权限清单、样本数据或长期记忆正文。</p>
     */
    @GetMapping("/skill-visibility-snapshots")
    public PlatformApiResponse<AgentSkillVisibilitySnapshotProjectionQueryResponse> querySkillVisibilitySnapshots(
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
                skillVisibilitySnapshotProjectionService.querySnapshots(query, accessContext),
                traceId
        );
    }

    /**
     * 查询多 Agent 会话调度 runtime event 的强类型投影视图。
     *
     * <p>该接口服务“本轮会话由哪些 Agent 参与、谁是主控、谁需要 handoff、是否出现降级”的治理场景。
     * 它固定读取 `agent_session_scheduling_recorded` 事件，并把 attributes 解析为低敏强类型 DTO。</p>
     *
     * <p>产品用途示例：</p>
     * <p>1. Agent 会话详情页展示 Master/Specialist/Guardrail/Observer 参与情况；</p>
     * <p>2. 审计员排查某次高风险任务为什么进入人工 handoff；</p>
     * <p>3. 运营台统计哪些领域 Agent、工具和 Skill 经常参与；</p>
     * <p>4. 后续 Master Agent handoff DAG 可以基于该视图回放“调度阶段已经做过什么”。</p>
     *
     * <p>安全边界：该接口不返回用户 objective、prompt、工具参数、SQL、样本数据、模型输出或长期记忆正文。
     * 返回字段只包含角色、状态、工具名、Skill code、记忆类型和计数等低敏控制面事实。</p>
     */
    @GetMapping("/agent-session-scheduling-snapshots")
    public PlatformApiResponse<AgentSessionSchedulingProjectionQueryResponse> queryAgentSessionSchedulingSnapshots(
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
                agentSessionSchedulingProjectionService.querySnapshots(query, accessContext),
                traceId
        );
    }

    /**
     * 查询模型网关路由 runtime event 的强类型投影视图。
     *
     * <p>该接口服务“本轮模型为什么选这个 Provider、是否 fallback、预算是否阻断、cache plan 是否启用”的治理场景。
     * 它固定读取 `model_gateway_routed` 事件，并把 attributes 转换成低敏强类型 DTO。</p>
     *
     * <p>产品用途示例：</p>
     * <p>1. Agent 运行详情页解释“为什么本轮从主 Provider 切到了备用 Provider”；</p>
     * <p>2. 审计台确认某次高风险治理任务使用的是哪个模型和哪种健康状态；</p>
     * <p>3. 运维台结合 Provider health 指标排查 fallback 峰值和 UNKNOWN 健康状态；</p>
     * <p>4. 未来 WebSocket timeline 可以直接展示该 display，不需要前端解析自由 Map。</p>
     *
     * <p>安全边界：该接口不返回 prompt、messages、SQL、工具参数、模型输出、URL、API Key、真实 cache key、
     * isolationKey 或 KV cache 内容。Provider 名称和模型名称属于控制面事实，仍会经过租户/项目/本人范围收口。</p>
     */
    @GetMapping("/model-gateway-routing-snapshots")
    public PlatformApiResponse<AgentModelGatewayRoutingProjectionQueryResponse> queryModelGatewayRoutingSnapshots(
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
                agentModelGatewayRoutingProjectionService.querySnapshots(query, accessContext),
                traceId
        );
    }

    /**
     * 查询 Master Agent handoff DAG 控制面视图。
     *
     * <p>该接口承接 `/agent-session-scheduling-snapshots` 的调度事实，但返回更接近产品管理台的
     * “Master -> Specialist -> Guardrail/Approval -> Tool Control Plane -> Feedback -> Second Turn” 图。
     * 它的定位是只读解释与审计，不执行 Agent、不调用工具、不创建审批单、不向 Kafka 投递命令。</p>
     *
     * <p>为什么需要单独接口而不是前端自己拼图：handoff DAG 涉及安全边界和产品语义，例如审批阻塞、
     * 工具副作用治理、记忆正文脱敏、降级状态不可自动执行等。如果这些规则散落在前端，会导致不同页面
     * 对同一次会话给出不同解释；放在 Java 控制面可以让运行详情、审计、审批和未来执行器使用同一套口径。</p>
     *
     * <p>当前边界：第一版只消费低敏 runtime event projection，不读取 prompt、SQL、工具参数、样本数据、
     * 模型输出或长期记忆正文。`executable=true` 也只代表“可进入后续执行候选”，真实执行仍必须经过权限、
     * 限流、幂等、工具预算、下游健康和审批状态校验。</p>
     */
    @GetMapping("/agent-session-handoff-dags")
    public PlatformApiResponse<AgentSessionHandoffDagQueryResponse> queryAgentSessionHandoffDags(
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
                agentSessionHandoffDagService.queryHandoffDags(query, accessContext),
                traceId
        );
    }

    /**
     * 查询 Skill 可见性快照专用索引诊断信息。
     *
     * <p>该接口回答“Skill 可见性索引链路是否健康”，不是查询某个用户会话的 Skill 明细。
     * 它返回配置 store、实际查询来源、索引大小探测状态、物化/重复/跳过/失败计数、查询来源计数和
     * Manifest 绑定状态分布。生产上该接口应由 gateway + permission-admin 保护在平台管理员、
     * 运维或审计员权限下。</p>
     *
     * <p>安全边界：诊断视图不返回 runId、sessionId、requestId、traceId、tenantId、projectId、
     * actorId、manifestFingerprint 或 Skill code 明细，因此可以安全用于管理端健康卡片和告警排障。
     * 真正的会话级 Skill 明细仍走 `/skill-visibility-snapshots` 并经过数据范围收口。</p>
     */
    @GetMapping("/skill-visibility-snapshots/diagnostics")
    public PlatformApiResponse<AgentSkillVisibilitySnapshotIndexDiagnosticsView> skillVisibilitySnapshotIndexDiagnostics(
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(skillVisibilitySnapshotProjectionService.diagnostics(), traceId);
    }

    /**
     * 按 run/session 执行 runtime event 增量回放。
     *
     * <p>该接口是 WebSocket 实时事件通道的 HTTP replay 底座。它与普通查询接口的区别是：
     * 1. 必须至少传入 runId 或 sessionId，避免客户端无边界拉取全量热窗口；
     * 2. 支持 clientId，当请求未显式传 afterSequence 时，会使用该客户端上次 ack 的 cursor；
     * 3. 响应中返回 effectiveAfterSequence 和 cursor，方便 Python WebSocket 桥接或前端断线重连恢复。</p>
     *
     * <p>当前仍是 HTTP replay，不是长连接本身。这样做是刻意分层：Java 控制面先提供可信事件源、权限收口、
     * 脱敏和 display 展示解释；WebSocket 连接管理可以继续由 Python Runtime 或后续 Java WebSocket endpoint 复用。</p>
     */
    @GetMapping("/replay")
    public PlatformApiResponse<AgentRuntimeEventReplayResponse> replayEvents(
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestParam(value = "projectId", required = false) String projectId,
            @RequestParam(value = "actorId", required = false) String actorId,
            @RequestParam(value = "requestId", required = false) String requestId,
            @RequestParam(value = "runId", required = false) String runId,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "eventType", required = false) String eventType,
            @RequestParam(value = "severity", required = false) String severity,
            @RequestParam(value = "afterSequence", required = false) Long afterSequence,
            @RequestParam(value = "clientId", required = false) String clientId,
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
                eventType,
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
        return PlatformApiResponse.success(replayService.replay(query, accessContext, clientId), traceId);
    }

    /**
     * 提交 runtime event replay ack。
     *
     * <p>ack 是客户端对“已经处理到某个 replaySequence”的确认，不是普通查询动作。
     * 服务端会保证 cursor 只前进不后退，重复 ack 或旧 ack 会被识别为 `STALE_ACK_IGNORED`，避免断线重连后游标回退。</p>
     */
    @PostMapping("/replay/acks")
    public PlatformApiResponse<AgentRuntimeEventReplayCursorView> acknowledgeReplayCursor(
            @RequestBody AgentRuntimeEventReplayAckRequest request,
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
        return PlatformApiResponse.success(replayService.acknowledge(request, accessContext), traceId);
    }

    /**
     * 查询某个客户端在某个 run/session 订阅范围内的 ack cursor。
     *
     * <p>该接口主要服务排障、前端恢复和 WebSocket 桥接层启动前探测。查询 cursor 同样要经过数据范围收口，
     * 避免用户通过 clientId 猜测别的租户或项目的消费位置。</p>
     */
    @GetMapping("/replay/acks")
    public PlatformApiResponse<AgentRuntimeEventReplayCursorView> queryReplayCursor(
            @RequestParam(value = "clientId") String clientId,
            @RequestParam(value = "runId", required = false) String runId,
            @RequestParam(value = "sessionId", required = false) String sessionId,
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
        return PlatformApiResponse.success(replayService.cursor(clientId, runId, sessionId, accessContext), traceId);
    }

    /**
     * 查询 Agent runtime event consumer 诊断信息。
     *
     * <p>该接口不返回事件明细，只返回配置、计数和最近拒绝原因，适合运维确认：
     * consumer 是否启用、topic/groupId 是否正确、当前投影是否有数据、是否存在大量重复或拒绝。</p>
     */
    @GetMapping("/diagnostics")
    public PlatformApiResponse<AgentRuntimeEventConsumerDiagnosticsView> diagnostics(
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(queryService.diagnostics(), traceId);
    }
}
