/**
 * @Author : Cui
 * @Date: 2026/06/23 00:00
 * @Description DataSmart Govern Backend - AgentToolActionCommandWorkerReceiptController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionCommandWorkerReceiptRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionCommandWorkerReceiptResponse;
import com.czh.datasmart.govern.agent.service.runtime.AgentToolActionCommandWorkerReceiptService;
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
 * 受控命令 worker 回执内部控制器。
 *
 * <p>该控制器只处理服务间写回，不面向普通用户开放。真实生产环境中，这条路由应继续叠加
 * gateway 内网路由、服务账号签名、mTLS、内网 ACL 或服务网格策略，避免外部调用方伪造
 * “命令已执行成功”一类审计事实。控制器自身不做复杂业务判断，只负责把 path/header/body
 * 交给 service，由 service 统一执行低敏校验、幂等键生成、runtime event 写入和索引物化。</p>
 *
 * <p>为什么不复用 dry-run receipt 路由：
 * dry-run receipt 的业务语义是“执行前治理预演”，必须永远 sideEffectExecuted=false；
 * command-worker receipt 的业务语义是“worker 侧复核或受控执行后的事实回执”，在安全条件满足后
 * 可以声明 sideEffectExecuted=true。二者分开建模可以避免后续审计、恢复事实包和 timeline 状态解释混乱。</p>
 */
@RestController
@RequestMapping("/internal/agent-runtime")
@RequiredArgsConstructor
public class AgentToolActionCommandWorkerReceiptController {

    private final AgentToolActionCommandWorkerReceiptService receiptService;

    /**
     * 接收 command worker 的低敏执行回执。
     *
     * @param sessionId Agent 会话 ID，来自路由，用于把回执挂到正确的会话 timeline。
     * @param runId Agent Run ID，来自路由，用于把回执挂到正确的一次运行 timeline。
     * @param request worker 写回的低敏回执；不得包含命令行、stdout/stderr、路径、环境变量或工具实参。
     * @param traceId 跨服务 traceId，会进入统一响应和 runtime event requestId，便于排障串联。
     * @return 回执接收结果；重复幂等键会返回 accepted=true、duplicate=true。
     */
    @PostMapping("/sessions/{sessionId}/runs/{runId}/tool-executions/command-worker-receipts")
    public PlatformApiResponse<AgentToolActionCommandWorkerReceiptResponse> receiveCommandWorkerReceipt(
            @PathVariable("sessionId") String sessionId,
            @PathVariable("runId") String runId,
            @RequestBody AgentToolActionCommandWorkerReceiptRequest request,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        AgentToolActionCommandWorkerReceiptResponse response =
                receiptService.receive(sessionId, runId, traceId, request);
        return PlatformApiResponse.success(response, traceId);
    }
}
