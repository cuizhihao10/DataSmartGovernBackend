package com.czh.datasmart.govern.permission.service.support;

import com.czh.datasmart.govern.permission.controller.dto.PermissionDecisionRequest;
import com.czh.datasmart.govern.permission.controller.dto.PermissionDecisionResult;
import com.czh.datasmart.govern.permission.entity.PermissionDataScopePolicy;
import com.czh.datasmart.govern.permission.entity.PermissionRoutePolicy;
import com.czh.datasmart.govern.permission.support.PermissionRouteEffect;
import lombok.RequiredArgsConstructor;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import static com.czh.datasmart.govern.permission.service.support.PermissionAdminSupport.ANY_HTTP_METHOD;

/**
 * @Author : Cui
 * @Date: 2026/05/06 00:21
 * @Description DataSmart Govern Backend - PermissionDecisionSupport.java
 * @Version:1.0.0
 *
 * 权限访问判定支持组件。
 *
 * <p>该组件专门回答一个问题：某个角色在某个租户下访问某条路由是否允许。
 * 它不修改策略，也不关心管理后台如何展示矩阵。
 * 这样的边界非常重要，因为未来授权判定会成为高频路径，通常需要单独优化缓存、延迟和一致性。
 *
 * <p>当前判定策略保持保守：
 * 1. 找到当前角色在平台默认和租户范围内的启用路由策略；
 * 2. 按 HTTP 方法、路径模式、资源类型、业务动作匹配候选策略；
 * 3. 高优先级策略先命中，同优先级 DENY 优先于 ALLOW；
 * 4. 没有命中任何策略时默认拒绝。
 */
@Component
@RequiredArgsConstructor
public class PermissionDecisionSupport {

    /**
     * Spring 提供的高性能路径模式解析器。
     *
     * <p>早期版本只支持完全匹配和 `/**` 前缀匹配，足够表达 `/api/task/**` 这种模块级权限。
     * 但 data-sync 运维闭环需要更细的端点级语义，例如某个同步任务下的人工介入动作、
     * 某个同步任务和某次 execution 下的执行器回调动作。
     * 使用 PathPatternParser 后，permission-admin 可以识别单段通配 `*`、尾部通配 `/**` 和未来的 `{id}` 模板，
     * 从而让“查看同步任务”和“执行器回调/人工恢复”等高风险动作拥有不同策略。
     */
    private static final PathPatternParser PATH_PATTERN_PARSER = new PathPatternParser();

    private final PermissionQuerySupport querySupport;
    private final PermissionAuditSupport auditSupport;

    /**
     * 判定一次访问是否允许。
     *
     * @param request 权限判定请求，包含租户、角色、资源、动作、HTTP 方法和路径。
     * @param traceId 链路追踪 ID，用于把网关请求、业务服务请求和权限审计串联起来。
     */
    public PermissionDecisionResult evaluate(PermissionDecisionRequest request, String traceId) {
        PermissionRoutePolicy matchedRoute = findMatchedRoutePolicy(request);
        if (matchedRoute == null) {
            PermissionDecisionResult result = denied("没有命中任何启用的路由策略，按默认拒绝处理", null, null);
            auditSupport.saveDecisionAudit(request, traceId, result);
            return result;
        }

        if (PermissionRouteEffect.DENY.name().equals(matchedRoute.getEffect())) {
            PermissionDecisionResult result = denied("命中显式拒绝策略: " + matchedRoute.getPolicyName(), matchedRoute, null);
            auditSupport.saveDecisionAudit(request, traceId, result);
            return result;
        }

        PermissionDataScopePolicy dataScope = findBestDataScope(request);
        List<Long> authorizedProjectIds = resolveAuthorizedProjectIds(request, dataScope);
        PermissionDecisionResult result = new PermissionDecisionResult(
                true,
                "命中允许策略: " + matchedRoute.getPolicyName(),
                matchedRoute.getId(),
                matchedRoute.getEffect(),
                dataScope == null ? null : dataScope.getScopeLevel(),
                dataScope == null ? null : dataScope.getScopeExpression(),
                authorizedProjectIds,
                dataScope != null && Boolean.TRUE.equals(dataScope.getApprovalRequired())
        );
        auditSupport.saveDecisionAudit(request, traceId, result);
        return result;
    }

    private PermissionRoutePolicy findMatchedRoutePolicy(PermissionDecisionRequest request) {
        return querySupport.listRoutePolicies(request.getTenantId(), request.getActorRole())
                .stream()
                .filter(policy -> methodMatches(policy.getHttpMethod(), request.getHttpMethod()))
                .filter(policy -> pathMatches(policy.getPathPattern(), request.getRequestPath()))
                .filter(policy -> semanticMatches(policy.getResourceType(), request.getResourceType()))
                .filter(policy -> semanticMatches(policy.getAction(), request.getAction()))
                .sorted(Comparator
                        .comparing(PermissionRoutePolicy::getPriority, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(policy -> PermissionRouteEffect.DENY.name().equals(policy.getEffect()) ? 0 : 1))
                .findFirst()
                .orElse(null);
    }

    /**
     * 查找最合适的数据范围策略。
     *
     * <p>当前先取当前角色 + 资源类型下第一条启用策略。
     * 后续可以加入策略优先级、项目范围、字段级权限、敏感数据审批等更细规则。
     */
    private PermissionDataScopePolicy findBestDataScope(PermissionDecisionRequest request) {
        String resourceType = request.getResourceType();
        if (resourceType == null || resourceType.isBlank()) {
            return null;
        }
        return querySupport.listDataScopePolicies(request.getTenantId(), request.getActorRole(), resourceType)
                .stream()
                .findFirst()
                .orElse(null);
    }

    /**
     * 物化 PROJECT 数据范围所需的项目授权集合。
     *
     * <p>数据范围策略中的 `project_id IN ${actorProjectIds}` 是给管理员和审计人员看的规则表达式，
     * 但业务服务真正需要的是“本次请求允许哪些 projectId”。如果不在 permission-admin 这里物化，
     * 就会迫使 gateway 或 data-sync 去理解权限中心的项目成员表，造成跨模块耦合。
     *
     * <p>只有命中的数据范围是 PROJECT 时才查询成员表，避免 SELF/TENANT/PLATFORM 的高频判定多一次无意义数据库访问。
     * 如果项目负责人暂时没有任何项目授权，则返回空集合；下游 data-sync 会把这解释为 PROJECT 范围下无可见项目，
     * 这比退化成租户范围更安全。
     */
    private List<Long> resolveAuthorizedProjectIds(PermissionDecisionRequest request,
                                                   PermissionDataScopePolicy dataScope) {
        if (dataScope == null || dataScope.getScopeLevel() == null
                || !"PROJECT".equalsIgnoreCase(dataScope.getScopeLevel())) {
            return List.of();
        }
        return querySupport.listActorProjectIds(request.getTenantId(), request.getActorId());
    }

    private boolean methodMatches(String configuredMethod, String requestMethod) {
        if (configuredMethod == null || configuredMethod.isBlank()) {
            return false;
        }
        return ANY_HTTP_METHOD.equalsIgnoreCase(configuredMethod)
                || configuredMethod.equalsIgnoreCase(requestMethod);
    }

    /**
     * 判断资源类型或动作是否匹配。
     *
     * <p>这里允许策略字段为空，是为了兼容历史路由策略。
     * 例如平台管理员的 `/api/**` 策略不需要列出所有资源类型和动作；它作为高优先级平台级兜底即可。
     * 但一旦策略写了 resourceType 或 action，就必须和 gateway 传入的语义完全一致。
     */
    private boolean semanticMatches(String configuredValue, String requestValue) {
        if (configuredValue == null || configuredValue.isBlank()) {
            return true;
        }
        return requestValue != null && configuredValue.equalsIgnoreCase(requestValue);
    }

    /**
     * 判断路径是否匹配。
     *
     * <p>当前实现支持最常见的完全匹配和 `/**` 后缀通配。
     * 后续若需要 `/api/task/{id}/logs` 这类模板匹配，可替换为 Spring PathPatternParser。
     */
    private boolean pathMatches(String pattern, String requestPath) {
        if (pattern == null || requestPath == null) {
            return false;
        }
        try {
            return PATH_PATTERN_PARSER.parse(pattern).matches(PathContainer.parsePath(requestPath));
        } catch (IllegalArgumentException ignored) {
            /*
             * 兼容保护：如果数据库里存在历史非法 pattern，不让一次坏配置直接打断权限判定流程。
             * 这里回退到旧版简单匹配逻辑，便于管理员先通过矩阵接口发现并修复坏策略。
             */
        }
        if (pattern.endsWith("/**")) {
            String prefix = pattern.substring(0, pattern.length() - 3);
            return requestPath.equals(prefix) || requestPath.startsWith(prefix + "/");
        }
        return Objects.equals(pattern, requestPath);
    }

    private PermissionDecisionResult denied(String reason,
                                            PermissionRoutePolicy routePolicy,
                                            PermissionDataScopePolicy dataScopePolicy) {
        return new PermissionDecisionResult(
                false,
                reason,
                routePolicy == null ? null : routePolicy.getId(),
                routePolicy == null ? null : routePolicy.getEffect(),
                dataScopePolicy == null ? null : dataScopePolicy.getScopeLevel(),
                dataScopePolicy == null ? null : dataScopePolicy.getScopeExpression(),
                List.of(),
                dataScopePolicy != null && Boolean.TRUE.equals(dataScopePolicy.getApprovalRequired())
        );
    }
}
