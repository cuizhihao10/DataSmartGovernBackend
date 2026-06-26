/**
 * @Author : Cui
 * @Date: 2026/06/26 00:00
 * @Description DataSmart Govern Backend - AgentCommandTaskFinalStateReconciliationController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller;

import com.czh.datasmart.govern.agent.controller.dto.AgentCommandTaskFinalStateCallbackDispatchRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentCommandTaskFinalStateCallbackDispatchResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentCommandTaskFinalStateReconciliationResponse;
import com.czh.datasmart.govern.agent.service.runtime.AgentCommandTaskFinalStateCallbackDispatchService;
import com.czh.datasmart.govern.agent.service.runtime.AgentCommandTaskFinalStateReconciliationService;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventQueryAccessContext;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventQueryAccessContextResolver;
import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent command 任务最终态对账只读控制器。
 *
 * <p>该 Controller 面向智能网关、运维台和未来自动补偿 worker，提供一个低敏查询入口：
 * 按 commandId 查看最新 worker receipt 是否足以把 Agent 异步任务解释为成功、失败、退避、等待或补偿。</p>
 *
 * <p>为什么单独建 Controller，而不是塞进通用 runtime event Controller：</p>
 * <p>1. 通用 runtime event 负责事件时间线，最终态对账负责业务解释，职责不同；</p>
 * <p>2. 对账接口必须强制 commandId，禁止无界扫描；</p>
 * <p>3. 路由需要明确告诉调用方：这是 task/command 闭环判断，不是原始事件导出；</p>
 * <p>4. 拆分后文件更短，符合项目“单文件尽量低于 500 行、职责解耦”的规范。</p>
 *
 * <p>安全边界：接口只返回低敏状态、证据码、建议动作和 receipt 短指纹；不返回命令正文、工具参数、
 * stdout/stderr、payload body、SQL、prompt、样本数据、模型输出、凭据、token 明文、内部 endpoint 或签名 URL。</p>
 */
@RestController
@RequestMapping({"/agent-runtime/async-task-commands", "/api/agent/async-task-commands"})
@RequiredArgsConstructor
public class AgentCommandTaskFinalStateReconciliationController {

    private final AgentCommandTaskFinalStateReconciliationService reconciliationService;
    private final AgentCommandTaskFinalStateCallbackDispatchService callbackDispatchService;
    private final AgentRuntimeEventQueryAccessContextResolver accessContextResolver;

    /**
     * 查询某个 command 的任务最终态对账结论。
     *
     * <p>典型使用场景：</p>
     * <p>1. 智能网关在会话恢复时，判断异步命令是否已经有成功/失败 receipt；</p>
     * <p>2. 运维台按 commandId 排障，确认任务为什么还停留在 RUNNING/DEFERRED；</p>
     * <p>3. 未来自动补偿 worker 先调用本接口获得建议，再按幂等键触发真实状态回调；</p>
     * <p>4. 审计人员确认某次失败是执行前阻断、执行失败，还是容量退避。</p>
     *
     * @param commandId 必填命令 ID。
     * @param toolCode 可选工具编码，用于进一步避免跨工具误配。
     * @param tenantId 可选租户过滤条件，只能缩小 Header 中的可信范围。
     * @param projectId 可选项目过滤条件，PROJECT 范围下必须属于授权项目集合。
     * @param actorId 可选 actor 过滤条件，SELF 范围下会被强制收口。
     * @param runId 可选 Agent Run 条件，建议生产调用传入。
     * @param sessionId 可选 Agent Session 条件，建议生产调用传入。
     * @param limit 最大扫描 receipt 数量。
     * @param traceId 链路追踪 ID。
     * @param currentTenantId gateway 透传的当前租户。
     * @param currentActorId gateway 透传的当前 actor。
     * @param currentActorRole gateway 透传的角色。
     * @param dataScopeLevel permission-admin 物化出的数据范围。
     * @param authorizedProjectIds permission-admin 物化出的授权项目集合。
     * @return 统一响应包装的低敏对账结论。
     */
    @GetMapping("/final-state-reconciliations")
    public PlatformApiResponse<AgentCommandTaskFinalStateReconciliationResponse> reconcileFinalState(
            @RequestParam(value = "commandId", required = false) String commandId,
            @RequestParam(value = "toolCode", required = false) String toolCode,
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestParam(value = "projectId", required = false) String projectId,
            @RequestParam(value = "actorId", required = false) String actorId,
            @RequestParam(value = "runId", required = false) String runId,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) String currentTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) String currentActorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String currentActorRole,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false)
            String authorizedProjectIds) {
        AgentRuntimeEventQueryAccessContext accessContext = accessContextResolver.resolve(
                currentTenantId,
                currentActorId,
                currentActorRole,
                traceId,
                dataScopeLevel,
                authorizedProjectIds
        );
        return PlatformApiResponse.success(
                reconciliationService.reconcile(
                        commandId,
                        toolCode,
                        tenantId,
                        projectId,
                        actorId,
                        runId,
                        sessionId,
                        limit,
                        accessContext
                ),
                traceId
        );
    }

    /**
     * 投递某个 command 的最终态回调。
     *
     * <p>该接口是 5.108 “只读对账建议”之后的第一条受控写入桥。
     * 它不会绕过 task-management 直接改表，也不会读取工具输出或 artifact 正文；
     * 服务层会先重新调用对账服务，只有在 receipt 足以给出 callbackSuggestion、且 taskId/taskRunId/executorId
     * 等执行器协议字段齐备时，才会把建议映射到 task-management 的 complete/fail/defer/progress 回调。</p>
     *
     * <p>请求体默认 dryRun=true，意味着调用方第一次接入时只会看到“如果投递会怎样”，不会实际写任务状态。
     * 如果要真实投递，必须显式传 dryRun=false。这样可以降低运维台、脚本或未来自动补偿 worker 配置错误时
     * 批量推进任务状态的风险。</p>
     *
     * @param request command、收口条件和投递策略。
     * @param traceId 链路追踪 ID。
     * @param currentTenantId gateway 透传的当前租户。
     * @param currentActorId gateway 透传的当前 actor。
     * @param currentActorRole gateway 透传的角色。
     * @param dataScopeLevel permission-admin 物化出的数据范围。
     * @param authorizedProjectIds permission-admin 物化出的授权项目集合。
     * @return 最终态对账结果和低敏投递结果。
     */
    @PostMapping("/final-state-callback-dispatches")
    public PlatformApiResponse<AgentCommandTaskFinalStateCallbackDispatchResponse> dispatchFinalStateCallback(
            @Valid @RequestBody AgentCommandTaskFinalStateCallbackDispatchRequest request,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) String currentTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) String currentActorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String currentActorRole,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false)
            String authorizedProjectIds) {
        AgentRuntimeEventQueryAccessContext accessContext = accessContextResolver.resolve(
                currentTenantId,
                currentActorId,
                currentActorRole,
                traceId,
                dataScopeLevel,
                authorizedProjectIds
        );
        return PlatformApiResponse.success(
                "命令任务最终态回调投递请求已处理",
                callbackDispatchService.dispatch(request, accessContext, traceId),
                traceId
        );
    }
}
