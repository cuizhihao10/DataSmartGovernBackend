/**
 * @Author : Cui
 * @Date: 2026/05/24 00:00
 * @Description DataSmart Govern Backend - AgentPlanIngestionController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller;

import com.czh.datasmart.govern.agent.controller.dto.IngestAgentPlanRequest;
import com.czh.datasmart.govern.agent.controller.dto.IngestedAgentPlanView;
import com.czh.datasmart.govern.agent.service.AgentPlanIngestionService;
import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Python AgentPlan 接入控制器。
 *
 * <p>该控制器是 Python AI Runtime 与 Java agent-runtime 的第一条受控桥梁。
 * 它的路由语义不是“让 Java 帮 Python 执行工具”，而是“把 Python 已生成的计划纳入 Java 控制面”。
 *
 * <p>路由设计：
 * - `/agent-runtime/plan-ingestions`：服务内调试和微服务直连使用；
 * - `/api/agent/plan-ingestions`：通过 gateway 暴露给平台侧调用方使用。
 *
 * <p>权限建议：
 * 生产环境中该接口不应直接暴露给普通前端用户，而应只允许智能网关、Python Runtime 服务账号、
 * 平台管理员或受控内部调用方访问。普通用户仍通过会话/对话入口提交目标，由智能网关转发给 Python Runtime。
 */
@RestController
@RequestMapping({"/agent-runtime/plan-ingestions", "/api/agent/plan-ingestions"})
@RequiredArgsConstructor
public class AgentPlanIngestionController {

    private final AgentPlanIngestionService ingestionService;

    /**
     * 接入一份 Python AgentPlan 快照。
     *
     * <p>请求成功后会返回 Java session、run 和 toolAudits。
     * 调用方后续必须使用返回的 runId/auditId 进入审批、查询或工具执行接口；
     * 本接口不会在接入时自动调用 datasource-management、data-quality、task-management 等下游微服务。
     *
     * @param request Python Runtime 生成的计划快照。
     * @param traceId gateway 或上游服务透传的链路追踪 ID。
     * @return Java 控制面接入结果。
     */
    @PostMapping
    public PlatformApiResponse<IngestedAgentPlanView> ingest(
            @Valid @RequestBody IngestAgentPlanRequest request,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success("Python AgentPlan 已接入 Java 控制面", ingestionService.ingest(request, traceId), traceId);
    }
}
