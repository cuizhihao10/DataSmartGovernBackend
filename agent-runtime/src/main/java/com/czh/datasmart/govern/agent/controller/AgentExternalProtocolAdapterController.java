/**
 * @Author : Cui
 * @Date: 2026/06/06 01:19
 * @Description DataSmart Govern Backend - AgentExternalProtocolAdapterController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller;

import com.czh.datasmart.govern.agent.controller.dto.AgentA2aPublicAgentCardView;
import com.czh.datasmart.govern.agent.controller.dto.AgentA2aTaskRuntimeEventContractPreviewResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentA2aTaskStateMachinePreviewResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentExternalProtocolAdapterPreviewResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentMcpToolsListResponse;
import com.czh.datasmart.govern.agent.service.runtime.AgentA2aTaskRuntimeEventContractPreviewService;
import com.czh.datasmart.govern.agent.service.runtime.AgentA2aTaskStateMachinePreviewService;
import com.czh.datasmart.govern.agent.service.runtime.AgentExternalProtocolAdapterPreviewService;
import com.czh.datasmart.govern.agent.service.runtime.AgentExternalProtocolDiscoveryAuditContext;
import com.czh.datasmart.govern.agent.service.runtime.AgentExternalProtocolDiscoveryEventPublisher;
import com.czh.datasmart.govern.agent.service.runtime.AgentExternalProtocolDiscoveryService;
import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 外部 Agent 协议适配控制器。
 *
 * <p>本控制器当前只提供 MCP/A2A adapter preview，目标是让前端、运维、Python Runtime 和架构评审者
 * 看到 DataSmart 内部能力将如何映射到外部 Agent 协议。它不是 MCP JSON-RPC Server，也不是 A2A
 * task endpoint，不接受工具调用，不读取资源正文，不返回 prompt 正文。</p>
 *
 * <p>为什么单独建控制器：
 * runtime event projection、tool registry、skill registry 都已经有各自控制器。如果把外部协议预览继续
 * 塞到其中任何一个控制器里，职责会变得含混：外部协议不是纯事件查询，也不是单纯工具目录或 Skill 目录。
 * 单独控制器可以把“协议适配层”作为独立产品能力演进，后续再逐步加入 MCP tools/list、A2A Agent Card、
 * Agent Registry、签名校验和协议调用审计。</p>
 */
@RestController
@RequestMapping({
        "/agent-runtime/protocol-adapters",
        "/api/agent/protocol-adapters"
})
@RequiredArgsConstructor
public class AgentExternalProtocolAdapterController {

    private final AgentExternalProtocolAdapterPreviewService previewService;
    private final AgentExternalProtocolDiscoveryService discoveryService;
    private final AgentExternalProtocolDiscoveryEventPublisher discoveryEventPublisher;
    private final AgentA2aTaskStateMachinePreviewService a2aTaskStateMachinePreviewService;
    private final AgentA2aTaskRuntimeEventContractPreviewService a2aTaskRuntimeEventContractPreviewService;

    /**
     * 查询 MCP/A2A 外部协议适配预览。
     *
     * <p>路由语义：
     * `/mcp-a2a/preview` 表示“同时查看 MCP 与 A2A 的映射草案”。当前选择合并预览，是因为 DataSmart 的
     * Agent 能力天然同时涉及“Agent 到工具/资源/Prompt”（MCP）和“Agent 到 Agent 协作/委派”（A2A）。
     * 如果过早把两者拆成完全独立接口，容易让调用方忽略它们共享的权限、审批、审计和租户边界。</p>
     *
     * <p>参数语义：
     * - `domain`：按治理域过滤 Skill，例如 DATA_QUALITY、TASK_MANAGEMENT；
     * - `riskLevel`：按风险等级过滤 Skill，例如 LOW、MEDIUM、HIGH；
     * - `includeNonReady`：是否在诊断视图中包含非 READY 或禁用 Skill。默认 false，适合运行时安全视角；
     *   管理员排查为什么某些能力不能对外暴露时，可传 true。</p>
     *
     * <p>返回行为：
     * 返回 `PlatformApiResponse` 包装的只读 preview。响应中只包含低敏能力元数据、协议映射说明和下一步建议，
     * 不包含工具实参、prompt 正文、资源正文、下游 endpoint、模型输出、密钥或客户样例数据。</p>
     *
     * @param domain 可选治理域过滤
     * @param riskLevel 可选风险等级过滤
     * @param includeNonReady 是否包含非 READY Skill 的诊断视图
     * @param traceId 链路追踪 ID，透传给统一响应
     */
    @GetMapping("/mcp-a2a/preview")
    public PlatformApiResponse<AgentExternalProtocolAdapterPreviewResponse> previewMcpA2aAdapter(
            @RequestParam(value = "domain", required = false) String domain,
            @RequestParam(value = "riskLevel", required = false) String riskLevel,
            @RequestParam(value = "includeNonReady", required = false, defaultValue = "false") Boolean includeNonReady,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(
                previewService.buildPreview(domain, riskLevel, includeNonReady),
                traceId
        );
    }

    /**
     * 查询 MCP `tools/list` 风格的只读工具发现结果。
     *
     * <p>路由语义：
     * `/mcp/tools/list` 是 DataSmart Agent Runtime 面向外部 Agent 协议的一小步“发现接口”。
     * 它比 `/mcp-a2a/preview` 更接近 MCP `tools/list`，但仍不是完整 MCP JSON-RPC Server：
     * 当前不处理 initialize，不协商 capability，不接受 `tools/call`，也不会执行工具。</p>
     *
     * <p>参数语义：
     * - `domain`：可选业务域过滤，便于外部 Agent 或管理台只查看某类工具；
     * - `riskLevel`：可选风险等级过滤；
     * - `cursor`：分页游标，当前使用 `offset:N` 轻量格式；
     * - `limit`：分页大小，服务端会限制最大值，防止发现接口被误用成大批量导出；
     * - `requestId`：模拟 JSON-RPC id，便于调用方排查响应来源。</p>
     *
     * <p>安全边界：
     * 返回内容只包含工具名、低敏描述、输入 JSON Schema、治理注解和 taskSupport，不包含内部 endpoint、
     * targetService、example、工具实参、Prompt、资源正文、模型输出、密钥或客户样例数据。</p>
     */
    @GetMapping("/mcp/tools/list")
    public PlatformApiResponse<AgentMcpToolsListResponse> listMcpTools(
            @RequestParam(value = "domain", required = false) String domain,
            @RequestParam(value = "riskLevel", required = false) String riskLevel,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "requestId", required = false) String requestId,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) String tenantId,
            @RequestHeader(value = PlatformContextHeaders.WORKSPACE_ID, required = false) String workspaceId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) String actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.REQUEST_SOURCE, required = false) String requestSource,
            @RequestHeader(value = PlatformContextHeaders.SOURCE_SERVICE, required = false) String sourceService) {
        AgentMcpToolsListResponse response = discoveryService.listMcpTools(domain, riskLevel, cursor, limit, requestId);
        discoveryEventPublisher.publishMcpToolsList(
                auditContext(traceId, tenantId, workspaceId, actorId, actorRole, requestSource, sourceService),
                domain,
                riskLevel,
                cursor,
                limit,
                response
        );
        return PlatformApiResponse.success(response, traceId);
    }

    /**
     * 查询 A2A public Agent Card 的管理路径版本。
     *
     * <p>该接口返回内容与根路径 `/.well-known/agent-card.json` 同源，但保留统一响应包装和可选过滤参数，
     * 更适合管理台、自动化测试和部署前检查。真实外部 Agent 自动发现时，应优先使用根路径发现端点，
     * 并由网关负责公网域名、TLS、缓存、签名和租户可见性策略。</p>
     */
    @GetMapping("/a2a/agent-card")
    public PlatformApiResponse<AgentA2aPublicAgentCardView> getA2aAgentCard(
            @RequestParam(value = "domain", required = false) String domain,
            @RequestParam(value = "riskLevel", required = false) String riskLevel,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) String tenantId,
            @RequestHeader(value = PlatformContextHeaders.WORKSPACE_ID, required = false) String workspaceId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) String actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.REQUEST_SOURCE, required = false) String requestSource,
            @RequestHeader(value = PlatformContextHeaders.SOURCE_SERVICE, required = false) String sourceService) {
        AgentA2aPublicAgentCardView response = discoveryService.buildA2aPublicAgentCard(domain, riskLevel);
        discoveryEventPublisher.publishA2aAgentCard(
                auditContext(traceId, tenantId, workspaceId, actorId, actorRole, requestSource, sourceService),
                "MANAGEMENT",
                domain,
                riskLevel,
                response
        );
        return PlatformApiResponse.success(response, traceId);
    }

    /**
     * 查询 A2A Task 状态机只读预览。
     *
     * <p>路由语义：
     * `/a2a/task-state-machine` 表示“查看 DataSmart 将如何承接 A2A task 生命周期”，它服务于管理台、
     * 架构评审、自动化测试和后续 Python Runtime/网关对接，而不是 A2A 自动发现入口。真实外部 Agent 自动发现
     * 仍应读取 `/.well-known/agent-card.json`；状态机预览则用于解释 future task endpoint 的治理合同。</p>
     *
     * <p>为什么这一步不创建任务：
     * A2A task 涉及提交、轮询、取消、streaming、push notification、审批、worker pre-check、outbox 和终态审计。
     * 在这些边界没有固定前直接开放 `message:send`，很容易让协议适配层绕过 permission-admin 或工具沙箱。
     * 因此当前接口只返回状态、流转和策略说明，不写数据库、不发布事件、不投递 outbox、不执行工具。</p>
     *
     * <p>返回行为：
     * 响应使用统一 `PlatformApiResponse` 包装，内容只包含 A2A 标准状态、DataSmart 内部阶段、允许流转、
     * 取消/超时/幂等/权限/stream/push 策略和下一步建议；不包含 prompt、message parts、工具参数、
     * 资源正文、模型输出、内部 endpoint、SQL、sample data 或 secret。</p>
     *
     * @param traceId 链路追踪 ID，透传给统一响应；当前不会写 task 事件，仅用于调用方排查本次查询
     */
    @GetMapping("/a2a/task-state-machine")
    public PlatformApiResponse<AgentA2aTaskStateMachinePreviewResponse> getA2aTaskStateMachine(
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(
                a2aTaskStateMachinePreviewService.buildPreview(),
                traceId
        );
    }

    /**
     * 查询 A2A Task runtime event 契约只读预览。
     *
     * <p>路由语义：
     * `/a2a/task-runtime-event-contract` 表示“查看未来 A2A task 生命周期事件应该如何进入 DataSmart runtime event
     * 链路”。5.28 的 `/a2a/task-state-machine` 回答状态如何流转；本接口回答这些状态变化应该被记录成哪些低敏事件，
     * 以及事件如何被 A2A streaming、push notification、任务查询、审计回放和指标消费。</p>
     *
     * <p>为什么仍然只读：
     * 事件契约是未来真实 `message:send`、`tasks/cancel`、streaming 和 push 的共同字段边界。如果现在直接写真实事件，
     * 就会出现“没有真实 task，却有 task 事件”的假事实。因此当前接口只返回 schema/contract 预览，不写 runtime
     * projection、不写 Kafka、不写 task history、不触发 worker。</p>
     *
     * <p>返回行为：
     * 响应只包含事件类型、字段白名单、投递通道、排序回放策略和持久化分层说明；不会返回原始消息正文、工具输入正文、
     * artifact 正文、模型输出正文、查询语句、样例数据、凭证或内部端点。</p>
     *
     * @param traceId 链路追踪 ID，透传给统一响应；当前不会写入具体 task event
     */
    @GetMapping("/a2a/task-runtime-event-contract")
    public PlatformApiResponse<AgentA2aTaskRuntimeEventContractPreviewResponse> getA2aTaskRuntimeEventContract(
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(
                a2aTaskRuntimeEventContractPreviewService.buildPreview(),
                traceId
        );
    }

    /**
     * 将 HTTP Header 转为协议发现事件上下文。
     *
     * <p>控制器负责读取 Header，事件发布器负责写 runtime event。中间用一个明确的上下文对象连接，
     * 可以避免 publisher 依赖 Spring MVC，也避免 service 层被 HTTP 细节污染。</p>
     */
    private AgentExternalProtocolDiscoveryAuditContext auditContext(String traceId,
                                                                    String tenantId,
                                                                    String workspaceId,
                                                                    String actorId,
                                                                    String actorRole,
                                                                    String requestSource,
                                                                    String sourceService) {
        return new AgentExternalProtocolDiscoveryAuditContext(
                traceId,
                tenantId,
                workspaceId,
                actorId,
                actorRole,
                requestSource,
                sourceService
        );
    }
}
