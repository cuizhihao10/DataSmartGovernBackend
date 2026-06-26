/**
 * @Author : Cui
 * @Date: 2026/06/27 00:08
 * @Description DataSmart Govern Backend - AgentToolActionArtifactBodyReadGrantFactController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionArtifactBodyReadGrantQueryResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionArtifactBodyReadGrantRevokeRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionArtifactBodyReadGrantRevokeResponse;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventQueryAccessContext;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventQueryAccessContextResolver;
import com.czh.datasmart.govern.agent.service.runtime.AgentToolActionArtifactBodyReadGrantQueryService;
import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * artifact 正文读取 grant fact 管理控制器。
 *
 * <p>该控制器服务管理员排障、审计台和后续安全运营页面，回答两个问题：
 * 1. 某个 grantDecisionReference 或 commandId 对应的低敏授权事实是否存在；
 * 2. 当权限策略变化、artifact 隔离或人工止血时，是否可以撤销一条 grant fact。</p>
 *
 * <p>它不是 artifact 下载接口，也不是正文预览接口。所有响应都只包含低敏控制面事实，不返回 artifact 正文、
 * sample bytes、stdout/stderr、MinIO bucket/key、签名 URL、bearer token、prompt、SQL、工具参数、
 * 模型输出、凭据或内部 endpoint。真正读取正文仍必须走 body-read-final-check、object-store probe、
 * DLP/恶意内容扫描、下载审计、限速和对象存储 ACL。</p>
 *
 * <p>权限模型说明：gateway 和 permission-admin 负责入口授权；本控制器继续消费可信 Header，
 * 由服务层把 SELF/PROJECT/TENANT/PLATFORM 数据范围收口到查询条件。撤销前也会先按同一范围确认记录可见，
 * 避免通过 grant 引用跨项目撤销或探测他人事实。</p>
 */
@RestController
@RequestMapping({
        "/agent-runtime/tool-action-artifact-body-read-grants",
        "/api/agent/tool-action-artifact-body-read-grants"
})
@RequiredArgsConstructor
public class AgentToolActionArtifactBodyReadGrantFactController {

    private final AgentToolActionArtifactBodyReadGrantQueryService queryService;
    private final AgentRuntimeEventQueryAccessContextResolver accessContextResolver;

    /**
     * 查询 artifact 正文读取 grant fact。
     *
     * <p>本接口要求至少提供 {@code grantDecisionReference} 或 {@code commandId}。
     * 这是为了把本路由定位为“已知线索排障/审计”，而不是无界审计报表。后续如果需要按时间分页导出，
     * 应另建带 page/pageSize、导出审批、限流和审计日志的报表接口。</p>
     *
     * @param grantDecisionReference 可选低敏 grant 引用，适合单条排障。
     * @param commandId 可选命令 ID，适合按一次 command 输出排查多个 grant。
     * @param artifactReference 可选低敏 artifact 引用过滤，不是对象存储 key。
     * @param tenantId 可选租户过滤；服务层会与 Header 中可信租户范围求交集。
     * @param projectId 可选项目过滤；PROJECT 范围下必须落在授权项目集合内。
     * @param actorId 可选 actor 过滤；SELF 范围下会被强制收口为当前 actor。
     * @param runId 可选 run 过滤，用于避免跨运行混淆。
     * @param sessionId 可选 session 过滤，用于会话级排障。
     * @param toolCode 可选工具编码过滤，不包含工具参数。
     * @param status 可选状态过滤，例如 ACTIVE、EXPIRED、REVOKED。
     * @param limit 单次返回上限，服务层会限制在 1..500。
     * @return 低敏 grant fact 查询响应。
     */
    @GetMapping("/grants")
    public PlatformApiResponse<AgentToolActionArtifactBodyReadGrantQueryResponse> queryGrants(
            @RequestParam(value = "grantDecisionReference", required = false) String grantDecisionReference,
            @RequestParam(value = "commandId", required = false) String commandId,
            @RequestParam(value = "artifactReference", required = false) String artifactReference,
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestParam(value = "projectId", required = false) String projectId,
            @RequestParam(value = "actorId", required = false) String actorId,
            @RequestParam(value = "runId", required = false) String runId,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "toolCode", required = false) String toolCode,
            @RequestParam(value = "status", required = false) String status,
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
        return PlatformApiResponse.success(queryService.queryGrants(
                grantDecisionReference,
                commandId,
                artifactReference,
                tenantId,
                projectId,
                actorId,
                runId,
                sessionId,
                toolCode,
                status,
                limit,
                accessContext
        ), traceId);
    }

    /**
     * 撤销 artifact 正文读取 grant fact。
     *
     * <p>撤销是高风险管理动作：它会让后续 final-check/object-store probe 对同一 grant fail-closed。
     * 因此请求体只允许提供 grant 引用和机器可读原因码；操作者必须来自可信 Header，
     * 不能由请求体自报 revokedBy，避免普通调用方冒充管理员或服务账号。</p>
     *
     * @param request 撤销请求，包含 grantDecisionReference 与 reasonCode。
     * @return 撤销后的低敏事实视图。
     */
    @PostMapping("/grants/revoke")
    public PlatformApiResponse<AgentToolActionArtifactBodyReadGrantRevokeResponse> revokeGrant(
            @RequestBody AgentToolActionArtifactBodyReadGrantRevokeRequest request,
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
        AgentToolActionArtifactBodyReadGrantRevokeResponse response = queryService.revoke(
                request == null ? null : request.grantDecisionReference(),
                request == null ? null : request.reasonCode(),
                accessContext
        );
        return PlatformApiResponse.success("artifact 正文读取 grant fact 已撤销", response, traceId);
    }
}
