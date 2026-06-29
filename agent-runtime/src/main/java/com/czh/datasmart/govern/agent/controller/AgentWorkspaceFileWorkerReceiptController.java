/**
 * @Author : Cui
 * @Date: 2026/06/29 00:00
 * @Description DataSmart Govern Backend - AgentWorkspaceFileWorkerReceiptController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller;

import com.czh.datasmart.govern.agent.controller.dto.AgentWorkspaceFileWorkerReceiptRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentWorkspaceFileWorkerReceiptResponse;
import com.czh.datasmart.govern.agent.service.runtime.AgentWorkspaceFileWorkerReceiptService;
import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Workspace 文件工具 worker 回执内部控制器。
 *
 * <p>该控制器面向未来 workspace file worker、task-management worker 或 Agent Runtime 内部编排器。
 * 它不是普通用户接口，也不是文件读写接口。worker 已经在受控执行区完成预检、读取、写入、跳过、失败或补偿判断后，
 * 通过本路由把低敏回执写回 Java runtime event。</p>
 *
 * <p>路由保留 `/internal` 与 `/api/internal` 两种形态，方便本地调试和 gateway 内网代理统一配置。
 * 生产环境仍必须叠加服务账号签名、mTLS、内网 ACL 或服务网格策略，避免外部调用方伪造“文件工具执行成功”。</p>
 */
@RestController
@RequiredArgsConstructor
public class AgentWorkspaceFileWorkerReceiptController {

    private final AgentWorkspaceFileWorkerReceiptService receiptService;

    /**
     * 接收 workspace 文件工具 worker 回执。
     *
     * <p>Controller 不解析 payload body、不读取文件、不校验 artifact 对象，也不直接写 projection；
     * 它只把 sessionId/runId/traceId/request 交给 service。service 会先校验 payloadReference 与 workspace 工具语义，
     * 再委托通用 command worker receipt 服务写入 timeline 和 receipt index。</p>
     *
     * @param sessionId Agent 会话 ID。
     * @param runId Agent run ID。
     * @param request workspace 文件 worker 低敏回执请求。
     * @param traceId gateway 或内部调用方透传的链路追踪 ID。
     * @return 低敏回执接收结果。
     */
    @PostMapping({
            "/internal/agent-runtime/sessions/{sessionId}/runs/{runId}/tool-executions/workspace-file-worker-receipts",
            "/api/internal/agent-runtime/sessions/{sessionId}/runs/{runId}/tool-executions/workspace-file-worker-receipts"
    })
    public PlatformApiResponse<AgentWorkspaceFileWorkerReceiptResponse> receiveWorkspaceFileWorkerReceipt(
            @PathVariable("sessionId") String sessionId,
            @PathVariable("runId") String runId,
            @RequestBody AgentWorkspaceFileWorkerReceiptRequest request,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(receiptService.receive(sessionId, runId, traceId, request), traceId);
    }
}
