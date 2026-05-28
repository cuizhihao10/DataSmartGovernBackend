/**
 * @Author : Cui
 * @Date: 2026/05/28 18:00
 * @Description DataSmart Govern Backend - AgentToolExecutionEventOutboxController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionEventOutboxOperationRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionEventOutboxOperationResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionEventOutboxQueryResponse;
import com.czh.datasmart.govern.agent.event.outbox.AgentToolExecutionEventOutboxDiagnostics;
import com.czh.datasmart.govern.agent.service.outbox.AgentToolExecutionEventOutboxOperationService;
import com.czh.datasmart.govern.agent.service.outbox.AgentToolExecutionEventOutboxQueryService;
import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 工具执行事件 outbox 查询控制器。
 *
 * <p>该控制器面向运维诊断、Agent Run 详情页排障和后续 dispatcher 管理页。
 * 它回答的问题不是“工具最终执行结果是什么”，而是“工具状态事件是否已经进入待投递事件箱，是否堆积、失败或被阻断”。</p>
 *
 * <p>权限边界建议：该接口应由 gateway + permission-admin 收口到 AI_RUNTIME/DIAGNOSE 或 AI_RUNTIME/AUDIT_EVENTS。
 * 当前 agent-runtime 先暴露服务内 API 契约，避免后续前端、Python Runtime 和运维平台没有统一查询入口。</p>
 */
@RestController
@RequestMapping({
        "/agent-runtime/tool-execution-events/outbox",
        "/api/agent/tool-execution-events/outbox"
})
@RequiredArgsConstructor
public class AgentToolExecutionEventOutboxController {

    private final AgentToolExecutionEventOutboxQueryService queryService;
    private final AgentToolExecutionEventOutboxOperationService operationService;

    /**
     * 查询 outbox 记录列表。
     *
     * <p>常见使用方式：
     * 按 runId 查询某次 Agent Run 的工具事件是否都已进入 outbox；
     * 按 status=PENDING 查询是否存在待投递堆积；
     * 按 status=BLOCKED 查询是否出现 payload 过大、序列化失败等需要人工处理的问题。</p>
     */
    @GetMapping
    public PlatformApiResponse<AgentToolExecutionEventOutboxQueryResponse> query(
            @RequestParam(value = "runId", required = false) String runId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(queryService.query(runId, status, limit), traceId);
    }

    /**
     * 查询 outbox 诊断摘要。
     *
     * <p>该接口不返回事件明细，适合健康检查、运维面板和告警规则使用。
     * 例如 pendingRecords 持续增长代表 dispatcher 能力不足或下游不可用；
     * blockedRecords 增长则通常代表事件契约或 payload 安全策略需要修复。</p>
     */
    @GetMapping("/diagnostics")
    public PlatformApiResponse<AgentToolExecutionEventOutboxDiagnostics> diagnostics(
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(queryService.diagnostics(), traceId);
    }

    /**
     * 将失败或阻断事件重新入队。
     *
     * <p>该接口面向平台管理员/运维人员，不应暴露给普通 Agent 用户。
     * 典型使用场景是：下游 Kafka topic、审计中心配置或 payload 契约已经修复，管理员希望让 dispatcher 再次自动投递。
     * 服务层只允许 FAILED/BLOCKED 进入 PENDING，避免把仍在 PUBLISHING 的记录强行重放造成重复投递。</p>
     */
    @PostMapping("/{outboxId}/requeue")
    public PlatformApiResponse<AgentToolExecutionEventOutboxOperationResponse> requeue(
            @PathVariable("outboxId") String outboxId,
            @RequestBody(required = false) AgentToolExecutionEventOutboxOperationRequest request,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) String actorId,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(
                "outbox 事件已重新入队，等待 dispatcher 补偿投递",
                operationService.requeue(outboxId, request, actorId),
                traceId
        );
    }

    /**
     * 人工忽略失败或阻断事件。
     *
     * <p>忽略不是投递成功，而是把事件转入 IGNORED，表示管理员已经判断该事件不再需要自动补偿。
     * 生产环境中，该动作应由 gateway/permission-admin 限定为平台管理员或运维角色，并进入操作审计。</p>
     */
    @PostMapping("/{outboxId}/ignore")
    public PlatformApiResponse<AgentToolExecutionEventOutboxOperationResponse> ignore(
            @PathVariable("outboxId") String outboxId,
            @RequestBody(required = false) AgentToolExecutionEventOutboxOperationRequest request,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) String actorId,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(
                "outbox 事件已人工忽略并归档",
                operationService.ignore(outboxId, request, actorId),
                traceId
        );
    }

    /**
     * 追加人工处理备注。
     *
     * <p>备注用于记录排障判断，例如“等待下游 ACL 修复”或“客户确认该历史事件无需补发”。
     * 当前阶段备注写入 lastError 最近摘要；后续接入 outbox_operation_audit 表后，应保留完整操作历史。</p>
     */
    @PostMapping("/{outboxId}/notes")
    public PlatformApiResponse<AgentToolExecutionEventOutboxOperationResponse> appendNote(
            @PathVariable("outboxId") String outboxId,
            @RequestBody(required = false) AgentToolExecutionEventOutboxOperationRequest request,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) String actorId,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(
                "outbox 事件已追加人工处理备注",
                operationService.appendNote(outboxId, request, actorId),
                traceId
        );
    }
}
