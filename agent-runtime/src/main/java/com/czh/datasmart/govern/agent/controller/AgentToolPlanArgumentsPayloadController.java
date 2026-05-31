/**
 * @Author : Cui
 * @Date: 2026/05/31 18:16
 * @Description DataSmart Govern Backend - AgentToolPlanArgumentsPayloadController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolPlanArgumentsPayloadView;
import com.czh.datasmart.govern.agent.service.AgentToolPlanArgumentsPayloadService;
import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 工具计划参数内部解析控制器。
 *
 * <p>该控制器不是普通用户 API，而是 agent-runtime 与 task-management worker 之间的控制面接口。
 * 它解决的是“Kafka command 只传 payloadReference，worker 如何读取真实参数”的问题。</p>
 *
 * <p>路由设计说明：</p>
 * <p>1. `/internal/agent-runtime/**` 明确表示只能在服务间网络或网关内部路由中访问；</p>
 * <p>2. sessionId/runId/auditId 三段路径同时出现，避免只靠 auditId 读取跨会话数据；</p>
 * <p>3. 末尾固定 `plan-arguments`，说明返回的是计划参数快照，不是执行结果，也不会触发真实工具调用。</p>
 *
 * <p>生产化安全要求：</p>
 * <p>当前代码先落地协议与学习参考，后续必须在 gateway 或服务网格层增加服务账号鉴权、mTLS、签名或内网白名单。
 * 因为返回体中的 planArguments 可能包含敏感业务参数，不能被浏览器、普通用户或未经授权的内部脚本直接访问。</p>
 */
@RestController
@RequestMapping("/internal/agent-runtime")
@RequiredArgsConstructor
public class AgentToolPlanArgumentsPayloadController {

    private final AgentToolPlanArgumentsPayloadService payloadService;

    /**
     * 读取指定工具审计记录的计划参数快照。
     *
     * @param sessionId Agent 会话 ID。
     * @param runId Agent Run ID。
     * @param auditId 工具审计 ID。
     * @param traceId 当前链路追踪 ID，由 gateway 或服务间调用方透传。
     * @return 受控参数载荷。接口只读，不改变工具状态。
     */
    @GetMapping("/sessions/{sessionId}/runs/{runId}/tool-executions/{auditId}/plan-arguments")
    public PlatformApiResponse<AgentToolPlanArgumentsPayloadView> getPlanArgumentsPayload(
            @PathVariable("sessionId") String sessionId,
            @PathVariable("runId") String runId,
            @PathVariable("auditId") String auditId,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        payloadService.validatePayloadKind(AgentToolPlanArgumentsPayloadService.PAYLOAD_KIND_PLAN_ARGUMENTS);
        return PlatformApiResponse.success(payloadService.getPlanArgumentsPayload(sessionId, runId, auditId), traceId);
    }
}
