/**
 * @Author : Cui
 * @Date: 2026/06/24 01:40
 * @Description DataSmart Govern Backend - AgentCommandWorkerLeaseController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller;

import com.czh.datasmart.govern.agent.controller.dto.AgentCommandWorkerLeaseClaimRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentCommandWorkerLeaseClaimResponse;
import com.czh.datasmart.govern.agent.service.runtime.AgentCommandWorkerLeaseClaimResult;
import com.czh.datasmart.govern.agent.service.runtime.AgentCommandWorkerLeaseRecord;
import com.czh.datasmart.govern.agent.service.runtime.AgentCommandWorkerLeaseService;
import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * command worker lease 内部控制器。
 *
 * <p>该路由是 worker 进入真实副作用区前的领取入口，不是普通用户接口。真实部署时应叠加内网路由、服务账号签名、
 * mTLS 或服务网格 ACL。控制器只负责 path/body 到 service 的编排，具体并发判断、token 生成和低敏校验由
 * {@link AgentCommandWorkerLeaseService} 统一处理。</p>
 */
@RestController
public class AgentCommandWorkerLeaseController {

    private final AgentCommandWorkerLeaseService leaseService;

    public AgentCommandWorkerLeaseController(AgentCommandWorkerLeaseService leaseService) {
        this.leaseService = leaseService;
    }

    /**
     * 领取 command worker lease。
     *
     * <p>成功响应中的 fencingToken 必须被 worker 原样带入后续 command worker receipt。被其他 worker 持有时，
     * 响应不会返回 token，调用方应停止处理并等待队列重试。</p>
     */
    @PostMapping({
            "/internal/agent-runtime/sessions/{sessionId}/runs/{runId}/tool-executions/command-worker-leases/claims",
            "/api/internal/agent-runtime/sessions/{sessionId}/runs/{runId}/tool-executions/command-worker-leases/claims"
    })
    public PlatformApiResponse<AgentCommandWorkerLeaseClaimResponse> claim(@PathVariable String sessionId,
                                                                           @PathVariable String runId,
                                                                           @RequestBody AgentCommandWorkerLeaseClaimRequest request,
                                                                           @RequestHeader(value = PlatformContextHeaders.TRACE_ID,
                                                                                   required = false) String traceId) {
        AgentCommandWorkerLeaseClaimResult result = leaseService.claim(sessionId, runId, request);
        AgentCommandWorkerLeaseRecord record = result.record();
        return PlatformApiResponse.success(new AgentCommandWorkerLeaseClaimResponse(
                result.acquired(),
                result.state().name(),
                record == null ? request.commandId() : record.commandId(),
                record == null ? request.executorId() : record.executorId(),
                result.tokenVisible() && record != null ? record.fencingToken() : null,
                record == null ? null : record.leaseVersion(),
                record == null || record.leaseExpiresAt() == null ? null : record.leaseExpiresAt().toEpochMilli(),
                result.message()
        ), traceId);
    }
}
