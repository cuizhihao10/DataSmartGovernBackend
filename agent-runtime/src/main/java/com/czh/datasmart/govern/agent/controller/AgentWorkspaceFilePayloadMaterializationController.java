/**
 * @Author : Cui
 * @Date: 2026/06/29 00:00
 * @Description DataSmart Govern Backend - AgentWorkspaceFilePayloadMaterializationController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller;

import com.czh.datasmart.govern.agent.controller.dto.AgentWorkspaceFilePayloadMaterializationRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentWorkspaceFilePayloadMaterializationResponse;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventQueryAccessContext;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventQueryAccessContextResolver;
import com.czh.datasmart.govern.agent.service.runtime.AgentWorkspaceFilePayloadMaterializationService;
import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/**
 * Workspace 文件工具 payload 物化内部控制器。
 *
 * <p>这个 Controller 是 Python Runtime、Agent 编排器或未来 workspace worker 准备阶段调用的内部控制面入口。
 * 它不是文件读写接口，也不是 artifact 下载接口：请求体里虽然可能带有真实 relativePath、写入 content 或
 * contentReference，但响应永远只返回 payloadReference、摘要、大小、证据码和问题码。</p>
 *
 * <p>为什么需要单独 Controller，而不是让调用方直接调用 service 或 command writer：</p>
 * <p>1. HTTP 层必须消费 gateway/permission-admin 透传的可信 Header，并防止请求体扩大 tenant/project/actor 范围；</p>
 * <p>2. Java durable command writer 只应接收低敏引用，不能承担真实参数解析和路径/正文校验职责；</p>
 * <p>3. 后续 worker receipt、artifact grant、DLP 扫描和审批豁免都可以围绕同一个 payloadReference 串联；</p>
 * <p>4. 该路由保持 internal 前缀，便于生产环境继续叠加服务账号签名、mTLS、网关内网 ACL 和审计策略。</p>
 */
@RestController
@RequestMapping("/internal/agent-runtime/tool-action-workspace-files")
@RequiredArgsConstructor
public class AgentWorkspaceFilePayloadMaterializationController {

    private final AgentWorkspaceFilePayloadMaterializationService materializationService;
    private final AgentRuntimeEventQueryAccessContextResolver accessContextResolver;

    /**
     * 创建 workspace 文件工具 payload 物化记录。
     *
     * <p>该路由只做三件事：</p>
     * <p>1. 从可信 Header 解析当前 tenant、actor、role、dataScope 和 authorizedProjectIds；</p>
     * <p>2. 将请求体中的 tenant/project/actor 与 Header 范围求交集，禁止请求体自报更大的访问范围；</p>
     * <p>3. 调用领域 service 把真实文件参数写入服务端 payload store，并返回低敏 DTO。</p>
     *
     * <p>安全边界：响应不会返回 relativePath、content、contentReference 原值、workspace root、SQL、prompt、
     * 工具参数正文、模型输出、凭据或内部 endpoint。后续真实 worker 必须使用 payloadReference 在服务端内部读取，
     * 并继续等待 artifact grant、DLP/恶意内容扫描、审批/豁免和 worker receipt。</p>
     *
     * @param request workspace 文件工具参数物化请求。
     * @param traceId gateway 透传的链路追踪 ID。
     * @param currentTenantId 当前可信租户 ID。
     * @param currentActorId 当前可信 actor ID。
     * @param currentActorRole 当前可信 actor 角色。
     * @param dataScopeLevel permission-admin 判定的数据范围等级。
     * @param authorizedProjectIds PROJECT 范围下的授权项目 ID 集合。
     * @return 低敏 payload 物化结果。
     */
    @PostMapping("/payload-materializations")
    public PlatformApiResponse<AgentWorkspaceFilePayloadMaterializationResponse> materialize(
            @RequestBody(required = false) AgentWorkspaceFilePayloadMaterializationRequest request,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) String currentTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) String currentActorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String currentActorRole,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false)
            String authorizedProjectIds) {
        if (request == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "workspace 文件工具 payload 物化请求体不能为空");
        }
        AgentRuntimeEventQueryAccessContext accessContext = accessContextResolver.resolve(
                currentTenantId,
                currentActorId,
                currentActorRole,
                traceId,
                dataScopeLevel,
                authorizedProjectIds
        );
        AgentWorkspaceFilePayloadMaterializationService.AgentWorkspaceFilePayloadMaterializationResponse response =
                materializationService.materialize(toServiceRequest(request, accessContext));
        return PlatformApiResponse.success(
                AgentWorkspaceFilePayloadMaterializationResponse.from(response),
                traceId
        );
    }

    /**
     * 将 HTTP DTO 转成领域 service 请求，并在转换过程中完成身份范围收口。
     *
     * <p>这里坚持“Header 是可信边界，请求体只能缩小范围”的原则。原因是 workspace 文件工具属于高风险能力：
     * 真实路径和写入正文虽然不会出现在响应中，但一旦被物化到错误租户、错误项目或错误 actor 下，
     * 后续 worker、artifact grant、审计和回放都会串到错误的数据边界。与其在 worker 阶段才发现，不如在物化入口
     * 直接 fail-closed。</p>
     */
    private AgentWorkspaceFilePayloadMaterializationService.AgentWorkspaceFilePayloadMaterializationRequest toServiceRequest(
            AgentWorkspaceFilePayloadMaterializationRequest request,
            AgentRuntimeEventQueryAccessContext accessContext) {
        String tenantId = resolveTenantId(request.tenantId(), accessContext);
        String actorId = resolveActorId(request.actorId(), accessContext);
        String projectId = resolveProjectId(request.projectId(), accessContext);
        return new AgentWorkspaceFilePayloadMaterializationService.AgentWorkspaceFilePayloadMaterializationRequest(
                request.payloadReference(),
                request.runId(),
                request.payloadKey(),
                tenantId,
                projectId,
                actorId,
                request.toolName(),
                request.operation(),
                request.graphId(),
                request.contractId(),
                request.relativePath(),
                request.content(),
                request.contentReference(),
                request.overwrite(),
                request.expectedSha256(),
                request.maxInlineContentBytes(),
                ttl(request.ttlSeconds())
        );
    }

    private String resolveTenantId(String requestTenantId, AgentRuntimeEventQueryAccessContext accessContext) {
        String bodyTenant = trimToNull(requestTenantId);
        String headerTenant = accessContext.tenantId() == null ? null : String.valueOf(accessContext.tenantId());
        if (headerTenant != null && bodyTenant != null && !headerTenant.equals(bodyTenant)) {
            throw new PlatformBusinessException(PlatformErrorCode.TENANT_SCOPE_DENIED,
                    "workspace 文件工具 payload 物化请求体 tenantId 与可信 Header 不一致");
        }
        String tenantId = bodyTenant == null ? headerTenant : bodyTenant;
        if (tenantId == null) {
            throw new PlatformBusinessException(PlatformErrorCode.UNAUTHORIZED,
                    "workspace 文件工具 payload 物化必须由 gateway 或内部服务传入可信租户上下文");
        }
        return tenantId;
    }

    private String resolveActorId(String requestActorId, AgentRuntimeEventQueryAccessContext accessContext) {
        String bodyActor = trimToNull(requestActorId);
        String headerActor = accessContext.actorId() == null ? null : String.valueOf(accessContext.actorId());
        if (headerActor != null && bodyActor != null && !headerActor.equals(bodyActor)) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "workspace 文件工具 payload 物化请求体 actorId 与可信 Header 不一致");
        }
        String actorId = bodyActor == null ? headerActor : bodyActor;
        if (actorId == null) {
            throw new PlatformBusinessException(PlatformErrorCode.UNAUTHORIZED,
                    "workspace 文件工具 payload 物化必须由 gateway 或内部服务传入可信 actor 上下文");
        }
        return actorId;
    }

    private String resolveProjectId(String requestProjectId, AgentRuntimeEventQueryAccessContext accessContext) {
        String projectId = trimToNull(requestProjectId);
        if (projectId == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "workspace 文件工具 payload 物化必须提供 projectId，避免跨项目工作区混用");
        }
        if (accessContext.explicitProjectScope()) {
            List<String> authorizedProjects = accessContext.authorizedProjectIdsAsStrings();
            if (!authorizedProjects.contains(projectId)) {
                throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                        "workspace 文件工具 payload 物化 projectId 不在当前授权项目集合内");
            }
        }
        return projectId;
    }

    private Duration ttl(Long ttlSeconds) {
        if (ttlSeconds == null) {
            return null;
        }
        if (ttlSeconds <= 0) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "workspace 文件工具 payload 物化 ttlSeconds 必须大于 0");
        }
        return Duration.ofSeconds(ttlSeconds);
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
