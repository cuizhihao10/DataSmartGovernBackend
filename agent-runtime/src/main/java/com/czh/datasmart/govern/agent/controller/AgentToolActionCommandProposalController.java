/**
 * @Author : Cui
 * @Date: 2026/06/07 14:48
 * @Description DataSmart Govern Backend - AgentToolActionCommandProposalController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionCommandProposalRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionCommandProposalResponse;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventQueryAccessContext;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventQueryAccessContextResolver;
import com.czh.datasmart.govern.agent.service.runtime.AgentToolActionCommandProposalService;
import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工具动作 command proposal 控制器。
 *
 * <p>本控制器是执行图到正式 outbox writer 之间的预备入口。调用方提交 graphId/contractId 和低敏证据，
 * 服务端返回是否满足进入正式 writer 的最低条件。它仍然不会写 outbox，也不会读取 payloadReference，更不会调用 worker。
 * 这样设计可以让前端确认页、智能网关和未来 execution graph runner 先获得明确的入箱前检查结果。</p>
 */
@RestController
@RequestMapping({"/agent-runtime/tool-action-commands", "/api/agent/tool-action-commands"})
@RequiredArgsConstructor
public class AgentToolActionCommandProposalController {

    private final AgentToolActionCommandProposalService proposalService;
    private final AgentRuntimeEventQueryAccessContextResolver accessContextResolver;

    /**
     * 生成工具动作 command proposal。
     *
     * <p>POST 语义表示调用方提交了一组“准备进入 command builder 的低敏证据”。但本接口不是写接口：
     * 不产生 outbox record，不创建审批记录，不更新 runtime event，也不触发 worker。正式写入必须由后续专用 writer
     * 使用 proposal/graph/contract 再次复核后完成。</p>
     */
    @PostMapping("/proposals")
    public PlatformApiResponse<AgentToolActionCommandProposalResponse> propose(
            @RequestBody(required = false) AgentToolActionCommandProposalRequest request,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) String currentTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) String currentActorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String currentActorRole,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        AgentRuntimeEventQueryAccessContext accessContext = accessContextResolver.resolve(
                currentTenantId,
                currentActorId,
                currentActorRole,
                traceId,
                dataScopeLevel,
                authorizedProjectIds
        );
        return PlatformApiResponse.success(proposalService.propose(request, accessContext), traceId);
    }
}
