/**
 * @Author : Cui
 * @Date: 2026/06/28 22:45
 * @Description DataSmart Govern Backend - AgentToolActionSubmissionFactRecoveryController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionSubmissionFactQueryResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionSubmissionManualResolutionRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionSubmissionManualResolutionResponse;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventQueryAccessContext;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventQueryAccessContextResolver;
import com.czh.datasmart.govern.agent.service.runtime.AgentToolActionSubmissionFactRecoveryService;
import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 工具真实提交事实恢复控制器。
 *
 * <p>该控制器面向智能网关、运维台和后续自动补偿 worker，提供 `UNKNOWN` 提交事实的最小可运营入口。
 * 这里的“恢复”不是重新执行工具，也不是重新调用 data-quality；它只是把人工对账后的低敏结论写回提交事实，
 * 让后续重复请求不会继续卡在 UNKNOWN，也不会盲目重放真实副作用。</p>
 *
 * <p>为什么不把该路由塞进质量治理 submit controller：</p>
 * <p>1. submit controller 是真实副作用入口，职责是“执行”；本控制器是运营恢复入口，职责是“对账后解释和修正事实”；</p>
 * <p>2. submit controller 的文件已经接近 500 行，如果继续堆管理能力会破坏解耦和可读性；</p>
 * <p>3. 恢复入口未来可能服务更多工具类型，不应绑定在 quality-remediation-submit 这一个业务工具上。</p>
 *
 * <p>安全边界：所有接口必须按 `commandId` 定位，不提供全量扫描；查询和恢复都会消费 gateway 透传的
 * tenant/actor/role/data-scope Header，并在服务层做二次收口。响应只返回低敏字段和短指纹，不返回 payload body、
 * prompt、SQL、样本、模型输出、凭据、stdout/stderr 或完整内部 URL。</p>
 */
@RestController
@RequestMapping({"/agent-runtime/tool-action-submissions", "/api/agent/tool-action-submissions"})
@RequiredArgsConstructor
public class AgentToolActionSubmissionFactRecoveryController {

    private final AgentToolActionSubmissionFactRecoveryService recoveryService;
    private final AgentRuntimeEventQueryAccessContextResolver accessContextResolver;

    /**
     * 查询某个 command 的真实提交事实。
     *
     * <p>典型使用场景：</p>
     * <p>1. task-management worker 发现 agent-runtime 返回 UNKNOWN 冲突后，运维按 commandId 查看事实；</p>
     * <p>2. 告警中心提示某个真实副作用提交不确定后，运维确认 idempotencyKey 和下游 taskId；</p>
     * <p>3. 后续自动补偿 worker 在执行前先查询当前事实，避免重复处理终态提交。</p>
     *
     * @param commandId 路径中的 commandId，必须精确提供。
     * @param tenantId 可选租户收口条件。
     * @param projectId 可选项目收口条件。
     * @param actorId 可选 actor 收口条件。
     * @param runId 可选 run 收口条件。
     * @param sessionId 可选 session 收口条件。
     * @param traceId 链路追踪 ID。
     * @param currentTenantId gateway 透传的当前租户。
     * @param currentActorId gateway 透传的当前 actor。
     * @param currentActorRole gateway 透传的当前角色。
     * @param dataScopeLevel permission-admin 物化的数据范围。
     * @param authorizedProjectIds permission-admin 物化的授权项目集合。
     * @return 统一响应包装的低敏提交事实查询结果。
     */
    @GetMapping("/{commandId}")
    public PlatformApiResponse<AgentToolActionSubmissionFactQueryResponse> query(
            @PathVariable("commandId") String commandId,
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestParam(value = "projectId", required = false) String projectId,
            @RequestParam(value = "actorId", required = false) String actorId,
            @RequestParam(value = "runId", required = false) String runId,
            @RequestParam(value = "sessionId", required = false) String sessionId,
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
                recoveryService.query(commandId, tenantId, projectId, actorId, runId, sessionId, accessContext),
                traceId
        );
    }

    /**
     * 人工对账后恢复 UNKNOWN 提交事实。
     *
     * <p>该路由默认只做 dry-run 预览。真实更新必须在请求体中显式传 `dryRun=false`。
     * 这样设计是为了让运维台可以先展示“会从 UNKNOWN 变成什么”，再由具备权限的操作者二次确认。</p>
     *
     * @param commandId 路径中的 commandId。
     * @param request 人工对账请求。
     * @param traceId 链路追踪 ID。
     * @param currentTenantId gateway 透传的当前租户。
     * @param currentActorId gateway 透传的当前 actor。
     * @param currentActorRole gateway 透传的当前角色。
     * @param dataScopeLevel permission-admin 物化的数据范围。
     * @param authorizedProjectIds permission-admin 物化的授权项目集合。
     * @return 统一响应包装的人工恢复结果。
     */
    @PostMapping("/{commandId}/manual-resolutions")
    public PlatformApiResponse<AgentToolActionSubmissionManualResolutionResponse> resolveUnknown(
            @PathVariable("commandId") String commandId,
            @Valid @RequestBody(required = false) AgentToolActionSubmissionManualResolutionRequest request,
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
                recoveryService.resolveUnknown(commandId, request, accessContext),
                traceId
        );
    }
}
