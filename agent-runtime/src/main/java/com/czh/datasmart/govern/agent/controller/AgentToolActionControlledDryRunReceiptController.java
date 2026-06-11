/**
 * @Author : Cui
 * @Date: 2026/06/11 22:20
 * @Description DataSmart Govern Backend - AgentToolActionControlledDryRunReceiptController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionControlledDryRunReceiptRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionControlledDryRunReceiptResponse;
import com.czh.datasmart.govern.agent.service.runtime.AgentToolActionControlledDryRunReceiptService;
import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 受控工具动作 dry-run receipt 内部回写控制器。
 *
 * <p>该控制器只服务 task-management 等内部控制面调用方，不面向普通用户开放。它的业务含义是：
 * 当 `AGENT_TOOL_ACTION_CONTROLLED` 任务被 dry-run 调度器认领并完成执行前判断后，task-management
 * 把低敏 receipt 写回 agent-runtime，agent-runtime 再把它纳入统一 runtime event timeline。</p>
 *
 * <p>为什么路径仍放在 `/internal/agent-runtime/**`：
 * receipt 是服务间事实同步，不是浏览器直接查询接口。生产环境应继续通过 gateway 内网路由、服务网格、
 * mTLS、服务账号签名或内网 ACL 保护该路由，避免外部调用方伪造“工具已被治理”的 timeline 事件。</p>
 */
@RestController
@RequestMapping("/internal/agent-runtime")
@RequiredArgsConstructor
public class AgentToolActionControlledDryRunReceiptController {

    private final AgentToolActionControlledDryRunReceiptService receiptService;

    /**
     * 接收 task-management 的受控工具动作 dry-run receipt。
     *
     * @param sessionId Agent 会话 ID，来自路径，确保 receipt 被挂到正确会话 timeline。
     * @param runId Agent Run ID，来自路径，确保 receipt 被挂到正确运行 timeline。
     * @param request 低敏 receipt 请求体，不允许包含真实工具参数或 payload body。
     * @param traceId 跨服务 traceId，会进入统一响应和 runtime event requestId。
     * @return receipt 接收结果与幂等信息。
     */
    @PostMapping("/sessions/{sessionId}/runs/{runId}/tool-executions/controlled-dry-run-receipts")
    public PlatformApiResponse<AgentToolActionControlledDryRunReceiptResponse> receiveControlledDryRunReceipt(
            @PathVariable("sessionId") String sessionId,
            @PathVariable("runId") String runId,
            @RequestBody AgentToolActionControlledDryRunReceiptRequest request,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        AgentToolActionControlledDryRunReceiptResponse response =
                receiptService.receive(sessionId, runId, traceId, request);
        return PlatformApiResponse.success(response, traceId);
    }
}
