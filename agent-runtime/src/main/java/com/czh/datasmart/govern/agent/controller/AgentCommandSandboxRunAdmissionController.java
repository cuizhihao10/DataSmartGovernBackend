/**
 * @Author : Cui
 * @Date: 2026/06/24 23:59
 * @Description DataSmart Govern Backend - AgentCommandSandboxRunAdmissionController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller;

import com.czh.datasmart.govern.agent.controller.dto.AgentCommandSandboxRunAdmissionRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentCommandSandboxRunAdmissionResponse;
import com.czh.datasmart.govern.agent.service.runtime.AgentCommandSandboxRunAdmissionService;
import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * command sandbox run 准入内部控制器。
 *
 * <p>该路由只给内部 worker/Python Runtime/未来 sandbox runner 使用，绝不是普通用户直接调用的“执行命令 API”。
 * 控制器不会接收命令正文，也不会启动进程；它只是把路由中的 sessionId/runId 与请求体中的低敏 lease、安全决策、
 * workspace 和资源预算交给 {@link AgentCommandSandboxRunAdmissionService} 统一判断。</p>
 *
 * <p>生产环境必须继续叠加内网访问、服务账号签名、mTLS、网关 ACL 或服务网格策略。这里的 controller 只是
 * 应用层合同，不负责网络边界；网络边界应该由 gateway、Kubernetes/Service Mesh 或部署层共同完成。</p>
 */
@RestController
@RequiredArgsConstructor
public class AgentCommandSandboxRunAdmissionController {

    private final AgentCommandSandboxRunAdmissionService admissionService;

    /**
     * 申请进入 command sandbox run。
     *
     * <p>路径中的 sessionId/runId 使该入口与 lease、output-sanitization、receipt 保持同一 URL 形态。
     * 这样 worker 的完整调用顺序可以稳定为：
     * claim lease -> sandbox admission -> run process in sandbox -> sanitize output -> write receipt -> release lease。
     * 当前方法只完成第二步。</p>
     *
     * @param sessionId Agent 会话 ID。
     * @param runId Agent run ID。
     * @param request 准入请求，只允许低敏 lease、策略和预算字段。
     * @param traceId 跨服务 traceId，只进入统一响应，不参与准入决策。
     * @return sandbox 准入合同；accepted=false 时 worker 必须禁止启动进程。
     */
    @PostMapping({
            "/internal/agent-runtime/sessions/{sessionId}/runs/{runId}/tool-executions/command-sandbox-run-admissions",
            "/api/internal/agent-runtime/sessions/{sessionId}/runs/{runId}/tool-executions/command-sandbox-run-admissions"
    })
    public PlatformApiResponse<AgentCommandSandboxRunAdmissionResponse> admit(@PathVariable String sessionId,
                                                                              @PathVariable String runId,
                                                                              @RequestBody AgentCommandSandboxRunAdmissionRequest request,
                                                                              @RequestHeader(value = PlatformContextHeaders.TRACE_ID,
                                                                                      required = false) String traceId) {
        AgentCommandSandboxRunAdmissionResponse response = admissionService.admit(sessionId, runId, request);
        return PlatformApiResponse.success(response, traceId);
    }
}
