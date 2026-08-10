/**
 * @Author : Cui
 * @Date: 2026/08/05 00:00
 * @Description DataSmart Govern Backend - SpecialistTurnFactController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.specialist;

import com.czh.datasmart.govern.agent.service.session.AgentSessionAccessContext;
import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformAuthorizedProjectHeaderSupport;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * 专业 Agent turn 事实控制器。
 *
 * <p>本控制器把“受信服务登记”和“当前用户读取”明确拆成两类入口：</p>
 * <ul>
 *     <li>POST 只允许通过 Agent Runtime 受信服务白名单和共享凭证，普通浏览器不能伪造模型或专业 Agent 的执行事实；</li>
 *     <li>GET 不接受请求参数中的 tenant/project/actor 覆盖值，只使用 Gateway 注入的上下文，并交给 Service 做对象级二次校验。</li>
 * </ul>
 *
 * <p>返回对象本身只包含 {@link SpecialistTurnFact} 允许的低敏字段，不会因为 Controller 的序列化而重新暴露
 * prompt、思维链、SQL、工具参数、凭据、样本或模型输出正文。</p>
 */
@RestController
@ConditionalOnBean(SpecialistTurnFactService.class)
@RequestMapping("/agent-runtime/specialist-turn-facts")
public class SpecialistTurnFactController {

    /**
     * 专业事实三层隔离所使用的应用 Header。
     *
     * <p>该常量暂时保留在本模块，是因为本次增量只允许修改 agent-runtime、gateway 与 permission-admin；
     * 值仍遵循平台统一的 {@code X-DataSmart-*} 命名。gateway 会在权限判定后删除来路值并重建它，
     * 因此 Controller 只能把它当作可信调用链快照的一部分，而不是客户端自行声明的应用归属。</p>
     */
    static final String APPLICATION_ID_HEADER = "X-DataSmart-Application-Id";

    /** 事实应用服务负责可信登记和查询范围收口。 */
    private final SpecialistTurnFactService service;

    public SpecialistTurnFactController(SpecialistTurnFactService service) {
        this.service = service;
    }

    /**
     * 登记或幂等更新一条专业 Agent turn 事实。
     *
     * <p>source-service 和 internal-service-token 只从 Header 读取，不放在请求体中；其中 token 会由 Gateway 清理、
     * 由受信服务链路注入。租户、应用、项目、actor 和 Agent 是完整的双主体责任链，必须与事实体完全一致；
     * delegation 是可选责任链，但一旦事实携带它，Header 也必须携带同一个值，防止受信服务调用时把错误范围写入数据库。</p>
     *
     * @param fact 低敏专业 Agent turn 事实
     * @param sourceService 受信服务来源名
     * @param internalToken 内部共享凭证
     * @param tenantId 当前调用上下文租户，必须匹配事实
     * @param applicationId 当前调用上下文应用，必须匹配事实
     * @param projectId 当前调用上下文项目，必须匹配事实
     * @param actorId 当前被代表的用户，必须匹配 fact.userId
     * @param agentId 当前执行专业 Agent，必须匹配 fact.agentId
     * @param delegationId 当前委托事实；事实有委托时必须匹配，事实无委托时也不得伪造额外委托
     * @param traceId 链路追踪 ID
     * @return 数据库最终保存的低敏事实
     */
    @PostMapping
    public PlatformApiResponse<SpecialistTurnFact> register(
            @RequestBody SpecialistTurnFact fact,
            @RequestHeader(value = PlatformContextHeaders.SOURCE_SERVICE, required = false) String sourceService,
            @RequestHeader(value = PlatformContextHeaders.INTERNAL_SERVICE_TOKEN, required = false) String internalToken,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = APPLICATION_ID_HEADER, required = false) Long applicationId,
            @RequestHeader(value = PlatformContextHeaders.PROJECT_ID, required = false) Long projectId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) String actorId,
            @RequestHeader(value = PlatformContextHeaders.AGENT_ID, required = false) String agentId,
            @RequestHeader(value = PlatformContextHeaders.AGENT_DELEGATION_ID, required = false) String delegationId,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        validateRegistrationContext(fact, tenantId, applicationId, projectId, actorId, agentId, delegationId);
        return PlatformApiResponse.success(
                "专业 Agent turn 事实已登记",
                service.register(fact, sourceService, internalToken),
                traceId
        );
    }

    /**
     * 按 session 查询当前用户可见的专业 Agent turn 事实。
     *
     * <p>sessionId 只是定位符，不是授权凭据。租户、项目、actor 和角色来自可信 Header，真正的对象归属判断在
     * Service 层完成。数据范围和项目角色也必须来自 Gateway 根据 permission-admin 结果重建的 Header；
     * 普通用户只能读自己的事实，项目/租户/平台审计读取必须通过显式范围合同。</p>
     */
    @GetMapping("/sessions/{sessionId}")
    public PlatformApiResponse<List<SpecialistTurnFact>> findBySession(
             @PathVariable String sessionId,
             @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
             @RequestHeader(value = APPLICATION_ID_HEADER, required = false) Long applicationId,
             @RequestHeader(value = PlatformContextHeaders.PROJECT_ID, required = false) Long projectId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) String actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false)
            String authorizedProjectIds,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_ROLES, required = false)
            String authorizedProjectRoles,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(
                service.findBySession(sessionId,
                        access(tenantId, applicationId, projectId, actorId, actorRole,
                                dataScopeLevel, authorizedProjectIds, authorizedProjectRoles),
                        100),
                traceId
        );
    }

    /** 按 run 查询当前用户可见的专业 Agent turn 事实，授权规则与 session 查询完全一致。 */
    @GetMapping("/runs/{runId}")
    public PlatformApiResponse<List<SpecialistTurnFact>> findByRun(
             @PathVariable String runId,
             @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
             @RequestHeader(value = APPLICATION_ID_HEADER, required = false) Long applicationId,
             @RequestHeader(value = PlatformContextHeaders.PROJECT_ID, required = false) Long projectId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) String actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false)
            String authorizedProjectIds,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_ROLES, required = false)
            String authorizedProjectRoles,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(
                service.findByRun(runId,
                        access(tenantId, applicationId, projectId, actorId, actorRole,
                                dataScopeLevel, authorizedProjectIds, authorizedProjectRoles),
                        100),
                traceId
        );
    }

    /**
     * 校验受信调用链传来的对象上下文。
     *
     * <p>专业事实表属于内部控制面，不能因为调用者碰巧持有可信 source/token 就允许写入一条没有
     * 归属责任链的事实。因此租户、项目、被代表用户和执行 Agent 现在始终必填；delegation 可以为空，
     * 但空值和非空值同样必须与事实精确一致。Python Runtime 已按该合同发送完整 Header，缺失上下文的旧调用会明确失败，
     * 而不是把“无法归属”当作可接受的全局事实。</p>
     */
    private void validateRegistrationContext(SpecialistTurnFact fact,
                                             Long tenantId,
                                             Long applicationId,
                                             Long projectId,
                                             String actorId,
                                             String agentId,
                                             String delegationId) {
        if (fact == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST, "专业 Agent turn 事实不能为空");
        }
        if (tenantId == null || tenantId <= 0
                || applicationId == null || applicationId <= 0
                || projectId == null || projectId <= 0
                || !hasText(actorId)
                || !hasText(agentId)) {
            throw new PlatformBusinessException(
                    PlatformErrorCode.FORBIDDEN,
                    "登记专业 Agent turn 事实时，租户、应用、项目、被代表用户和执行 Agent 上下文必须同时提供"
            );
        }
        if (!tenantId.equals(fact.tenantId())
                || !applicationId.equals(fact.applicationId())
                || !projectId.equals(fact.projectId())
                || !actorId.trim().equals(fact.userId())) {
            throw new PlatformBusinessException(
                    PlatformErrorCode.TENANT_SCOPE_DENIED,
                    "专业 Agent turn 事实的租户、应用、项目或用户与可信调用上下文不一致"
            );
        }
        // Agent 是与 userId 并列的第二审计主体。不能只相信 body 中的 agentId，
        // 否则受信服务错误重用请求对象时可能把 A Agent 的事实写成 B Agent 的工作结果。
        if (!agentId.trim().equals(fact.agentId())) {
            throw new PlatformBusinessException(
                    PlatformErrorCode.FORBIDDEN,
                    "专业 Agent turn 事实的 Agent 身份与可信调用上下文不一致"
            );
        }
        // delegation 是可选的，但不能只做“Header 出现时才比较”。那会让一个应受委托约束的事实
        // 在传输中丢失 Header 后仍被接受；这里连空值也精确比较，保持用户 -> Agent 的责任链完整。
        if (!Objects.equals(normalizeOptional(delegationId), fact.delegationId())) {
            throw new PlatformBusinessException(
                    PlatformErrorCode.FORBIDDEN,
                    "专业 Agent turn 事实的委托身份与可信调用上下文不一致"
            );
        }
    }

    /**
     * 把 Gateway 注入的当前访问 Header 组合成不可变上下文，禁止 Controller 使用请求参数扩大范围。
     *
     * <p>项目集合和项目角色 Header 只由 Gateway 在 permission-admin 判定后重建；这里仅解析成类型安全
     * 的快照，不根据 actorRole 自行推导 TENANT/PLATFORM 权限。</p>
     */
    private AgentSessionAccessContext access(Long tenantId,
                                              Long applicationId,
                                              Long projectId,
                                             String actorId,
                                             String actorRole,
                                             String dataScopeLevel,
                                             String authorizedProjectIds,
                                             String authorizedProjectRoles) {
        return new AgentSessionAccessContext(
                tenantId,
                applicationId,
                projectId,
                actorId,
                actorRole,
                dataScopeLevel,
                PlatformAuthorizedProjectHeaderSupport.parse(authorizedProjectIds),
                PlatformAuthorizedProjectHeaderSupport.parseRoles(authorizedProjectRoles)
        );
    }

    /** 空白 Header 不算作可信上下文。 */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 将可选 Header 统一为与领域对象一致的空值语义。
     *
     * <p>HTTP 客户端通常会省略空 delegation Header，而不是发送空字符串。将两种表示都归一化为
     * {@code null} 后再比较，既允许无委托的专业 Agent turn 正常登记，又不会把缺少必需委托误判为合法。</p>
     */
    private String normalizeOptional(String value) {
        return hasText(value) ? value.trim() : null;
    }
}
