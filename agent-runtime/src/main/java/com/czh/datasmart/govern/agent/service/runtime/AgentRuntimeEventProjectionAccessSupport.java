/**
 * @Author : Cui
 * @Date: 2026/05/27 20:15
 * @Description DataSmart Govern Backend - AgentRuntimeEventProjectionAccessSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Agent 运行时事件投影查询的数据范围收口组件。
 *
 * <p>gateway + permission-admin 已经负责回答“这个角色能不能访问事件查询入口”，但入口授权只解决了
 * “能不能进门”的问题；进入 agent-runtime 后，服务层还必须继续回答“进门后最多能看到哪些事件”。</p>
 *
 * <p>本组件负责把数据范围语义翻译成查询条件：</p>
 * <p>1. SELF：只能看当前 actorId 在当前 tenantId 下的事件；</p>
 * <p>2. PROJECT：只能看当前 tenantId 且 projectId 位于授权项目集合内的事件；</p>
 * <p>3. TENANT：只能看当前 tenantId 下的事件；</p>
 * <p>4. PLATFORM：可跨租户查询，但仍尊重用户主动传入的过滤条件。</p>
 *
 * <p>它不访问数据库、不调用远程权限中心，只做纯规则翻译，因此后续 MySQL/ClickHouse/审计中心持久化
 * 查询实现都可以复用同一套收口逻辑。</p>
 */
@Component
public class AgentRuntimeEventProjectionAccessSupport {

    private static final Map<String, String> FALLBACK_SCOPE_BY_ROLE = Map.of(
            "ORDINARY_USER", "SELF",
            "PROJECT_OWNER", "PROJECT",
            "OPERATOR", "TENANT",
            "AUDITOR", "TENANT",
            "TENANT_ADMINISTRATOR", "TENANT",
            "PLATFORM_ADMINISTRATOR", "PLATFORM",
            "SERVICE_ACCOUNT", "PLATFORM"
    );

    /**
     * 将用户原始查询条件收口为服务端可信查询条件。
     *
     * <p>注意：这里不是“覆盖用户参数让查询更方便”，而是把用户参数与权限范围求交集。用户可以通过
     * tenantId/projectId/actorId 缩小查询范围，但不能用这些参数扩大自己从 gateway 获得的数据范围。</p>
     */
    public AgentRuntimeEventProjectionQuery restrict(AgentRuntimeEventProjectionQuery original,
                                                     AgentRuntimeEventQueryAccessContext context) {
        if (original == null) {
            original = new AgentRuntimeEventProjectionQuery(null, null, null, null, null, null, null, null, null);
        }
        if (context == null || !context.hasIdentity()) {
            /*
             * 缺少基础身份时采取 fail-safe 空结果策略。
             * 对于 Agent 运行事件这种可能包含执行轨迹和工具调用信息的资源，宁可本地联调时显式补 Header，
             * 也不要在生产误配置时默认放开全量事件。
             */
            return withAuthorizedProjects(original, List.of());
        }

        String scopeLevel = resolveScopeLevel(context);
        return switch (scopeLevel) {
            case "PLATFORM" -> original;
            case "TENANT" -> restrictTenant(original, context);
            case "PROJECT" -> restrictProject(original, context);
            case "SELF" -> restrictSelf(original, context);
            default -> restrictTenant(original, context);
        };
    }

    private AgentRuntimeEventProjectionQuery restrictTenant(AgentRuntimeEventProjectionQuery original,
                                                           AgentRuntimeEventQueryAccessContext context) {
        String actorTenantId = String.valueOf(context.tenantId());
        assertRequestedValueWithinScope(original.tenantId(), actorTenantId, "tenantId");
        return copy(original, actorTenantId, original.projectId(), original.actorId(), null);
    }

    private AgentRuntimeEventProjectionQuery restrictSelf(AgentRuntimeEventProjectionQuery original,
                                                         AgentRuntimeEventQueryAccessContext context) {
        String actorTenantId = String.valueOf(context.tenantId());
        String actorId = String.valueOf(context.actorId());
        assertRequestedValueWithinScope(original.tenantId(), actorTenantId, "tenantId");
        assertRequestedValueWithinScope(original.actorId(), actorId, "actorId");
        return copy(original, actorTenantId, original.projectId(), actorId, null);
    }

    private AgentRuntimeEventProjectionQuery restrictProject(AgentRuntimeEventProjectionQuery original,
                                                            AgentRuntimeEventQueryAccessContext context) {
        String actorTenantId = String.valueOf(context.tenantId());
        assertRequestedValueWithinScope(original.tenantId(), actorTenantId, "tenantId");

        List<String> authorizedProjectIds = context.explicitProjectScope()
                ? context.authorizedProjectIdsAsStrings()
                : List.of();
        if (original.projectId() != null && !original.projectId().isBlank()) {
            if (!authorizedProjectIds.contains(original.projectId().trim())) {
                throw new PlatformBusinessException(PlatformErrorCode.TENANT_SCOPE_DENIED,
                        "当前身份不能查看未授权项目的 Agent 运行事件，projectId=" + original.projectId());
            }
            return copy(original, actorTenantId, original.projectId().trim(), original.actorId(), List.of(original.projectId().trim()));
        }
        return copy(original, actorTenantId, null, original.actorId(), authorizedProjectIds);
    }

    private void assertRequestedValueWithinScope(String requestedValue, String allowedValue, String fieldName) {
        if (requestedValue == null || requestedValue.isBlank()) {
            return;
        }
        if (!requestedValue.trim().equals(allowedValue)) {
            throw new PlatformBusinessException(PlatformErrorCode.TENANT_SCOPE_DENIED,
                    "当前身份不能通过 " + fieldName + " 扩大 Agent 运行事件查询范围，requested="
                            + requestedValue + ", allowed=" + allowedValue);
        }
    }

    private String resolveScopeLevel(AgentRuntimeEventQueryAccessContext context) {
        String explicitScope = context.normalizedDataScopeLevel();
        if (!explicitScope.isBlank()) {
            return explicitScope;
        }
        return FALLBACK_SCOPE_BY_ROLE.getOrDefault(context.normalizedRole(), "TENANT");
    }

    private AgentRuntimeEventProjectionQuery withAuthorizedProjects(AgentRuntimeEventProjectionQuery original,
                                                                    List<String> authorizedProjectIds) {
        return copy(original, original.tenantId(), original.projectId(), original.actorId(), authorizedProjectIds);
    }

    private AgentRuntimeEventProjectionQuery copy(AgentRuntimeEventProjectionQuery original,
                                                  String tenantId,
                                                  String projectId,
                                                  String actorId,
                                                  List<String> authorizedProjectIds) {
        return new AgentRuntimeEventProjectionQuery(
                trimToNull(tenantId),
                trimToNull(projectId),
                trimToNull(actorId),
                trimToNull(original.requestId()),
                trimToNull(original.runId()),
                trimToNull(original.sessionId()),
                normalizeOptionalCode(original.eventType()),
                normalizeOptionalCode(original.severity()),
                original.limit(),
                authorizedProjectIds
        );
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String normalizeOptionalCode(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
