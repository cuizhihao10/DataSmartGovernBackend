/**
 * @Author : Cui
 * @Date: 2026/05/28 18:00
 * @Description DataSmart Govern Backend - AgentToolExecutionEventOutboxController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionEventOutboxQueryResponse;
import com.czh.datasmart.govern.agent.event.outbox.AgentToolExecutionEventOutboxDiagnostics;
import com.czh.datasmart.govern.agent.service.outbox.AgentToolExecutionEventOutboxQueryService;
import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
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
}
