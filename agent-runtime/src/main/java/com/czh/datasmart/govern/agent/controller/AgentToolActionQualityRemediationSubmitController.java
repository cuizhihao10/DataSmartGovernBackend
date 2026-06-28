/**
 * @Author : Cui
 * @Date: 2026/06/28 23:10
 * @Description DataSmart Govern Backend - AgentToolActionQualityRemediationSubmitController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionQualityRemediationSubmitRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionQualityRemediationSubmitResponse;
import com.czh.datasmart.govern.agent.service.tool.QualityRemediationTaskCommandSubmissionService;
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
 * 质量治理受控命令提交内部控制器。
 *
 * <p>该控制器是 task-management `AGENT_TOOL_ACTION_CONTROLLED` worker 后续真实执行的 agent-runtime 入口。
 * 它只接受 commandId，不接受 payload body。这样正文始终留在 agent-runtime Host 的 payload store 中，
 * task-management 只负责调度和租约，不直接持有治理草案正文或工具参数。</p>
 *
 * <p>生产环境中该内部路由应继续叠加服务账号签名、mTLS、内网 ACL 或服务网格策略。控制器自身只做 HTTP 接入，
 * 真正的 outbox 状态、confirmation fact、payload store、TTL、policyVersion 和 data-quality 调用校验都放在 service。</p>
 */
@RestController
@RequestMapping("/internal/agent-runtime/tool-action-commands")
@RequiredArgsConstructor
public class AgentToolActionQualityRemediationSubmitController {

    private final QualityRemediationTaskCommandSubmissionService submissionService;

    /**
     * 按 commandId 提交已审批的质量治理任务。
     *
     * <p>该接口会产生真实业务副作用：当所有校验通过时，会调用 data-quality
     * `/quality-rules/remediation-tasks` 且 `dryRun=false`，由 data-quality 再提交 task-management。
     * 因此调用方必须先完成 task-management 任务认领、worker lease 和 command pre-check。</p>
     */
    @PostMapping("/{commandId}/quality-remediation-submit")
    public PlatformApiResponse<AgentToolActionQualityRemediationSubmitResponse> submit(
            @PathVariable("commandId") String commandId,
            @RequestBody(required = false) AgentToolActionQualityRemediationSubmitRequest request,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        AgentToolActionQualityRemediationSubmitResponse response =
                submissionService.submit(commandId, request, traceId);
        return PlatformApiResponse.success(response, traceId);
    }
}
