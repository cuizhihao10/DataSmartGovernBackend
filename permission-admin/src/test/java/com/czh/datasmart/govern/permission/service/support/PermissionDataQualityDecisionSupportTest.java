/**
 * @Author : Cui
 * @Date: 2026/06/28 10:45
 * @Description DataSmart Govern Backend - PermissionDataQualityDecisionSupportTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service.support;

import com.czh.datasmart.govern.permission.controller.dto.PermissionDecisionRequest;
import com.czh.datasmart.govern.permission.controller.dto.PermissionDecisionResult;
import com.czh.datasmart.govern.permission.entity.PermissionDataScopePolicy;
import com.czh.datasmart.govern.permission.entity.PermissionRoutePolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * data-quality 细粒度权限决策测试。
 *
 * <p>本测试类保护 permission-admin 对 data-quality 新资源类型的判定行为。质量模块现在已经拆成：
 * QUALITY_RULE、QUALITY_GOVERNANCE、QUALITY_REPORT、QUALITY_ANOMALY、QUALITY_EXECUTION。
 * 这种拆分不是为了“枚举更多名字”，而是为了让真实产品能够表达不同职责：
 * 普通用户看低敏治理态势、项目负责人管理规则和触发检测、运营人员诊断执行器、
 * 审计员只读复核证据、SERVICE_ACCOUNT 提交 worker 回调。</p>
 *
 * <p>测试不依赖 MySQL 初始化脚本，而是直接 mock {@link PermissionQuerySupport} 返回的策略集合。
 * 这样可以稳定验证 {@link PermissionDecisionSupport} 的核心算法：路由策略如何匹配、DENY 如何优先、
 * 数据范围如何返回、PROJECT 范围如何物化 authorizedProjectIds。</p>
 */
class PermissionDataQualityDecisionSupportTest {

    private PermissionQuerySupport querySupport;
    private PermissionAuditSupport auditSupport;
    private PermissionDecisionSupport decisionSupport;

    @BeforeEach
    void setUp() {
        querySupport = mock(PermissionQuerySupport.class);
        auditSupport = mock(PermissionAuditSupport.class);
        decisionSupport = new PermissionDecisionSupport(querySupport, auditSupport);
    }

    /**
     * 普通用户查看质量治理总览时，应允许访问并返回 PROJECT 数据范围。
     *
     * <p>治理总览虽然是低敏聚合视图，但仍然可能暴露某个项目质量风险较高、失败报告较多、
     * 异常字段集中等业务事实。因此普通用户不能直接获得 TENANT 或 PLATFORM 范围，而应通过
     * PROJECT 范围和项目成员授权表收口到自己被授权的项目集合。</p>
     */
    @Test
    void ordinaryUserShouldViewGovernanceOverviewWithProjectScope() {
        PermissionDecisionRequest request = request(
                "ORDINARY_USER",
                "GET",
                "/api/quality/quality-rules/governance/overview",
                "QUALITY_GOVERNANCE",
                "VIEW"
        );
        when(querySupport.listRoutePolicies(10L, "ORDINARY_USER"))
                .thenReturn(List.of(routePolicy(62001L, "ORDINARY_USER", "GET",
                        "/api/quality/quality-rules/governance/overview",
                        "QUALITY_GOVERNANCE", "VIEW", "ALLOW", 122,
                        "普通用户查看质量治理总览")));
        when(querySupport.listDataScopePolicies(10L, "ORDINARY_USER", "QUALITY_GOVERNANCE"))
                .thenReturn(List.of(dataScope("ORDINARY_USER", "QUALITY_GOVERNANCE",
                        "PROJECT", "project_id IN ${actorProjectIds}")));
        when(querySupport.listActorProjectIds(10L, 1001L)).thenReturn(List.of(101L, 102L));

        PermissionDecisionResult result = decisionSupport.evaluate(request, "trace-quality-governance");

        assertThat(result.getAllowed()).isTrue();
        assertThat(result.getMatchedRoutePolicyId()).isEqualTo(62001L);
        assertThat(result.getDataScopeLevel()).isEqualTo("PROJECT");
        assertThat(result.getDataScopeExpression()).isEqualTo("project_id IN ${actorProjectIds}");
        assertThat(result.getAuthorizedProjectIds()).containsExactly(101L, 102L);
        assertThat(result.getReason()).contains("普通用户查看质量治理总览");
    }

    /**
     * 运营人员诊断质量执行器时，应允许访问并返回 TENANT 数据范围。
     *
     * <p>运营人员处理的是租户内运行健康、积压和失败排障，通常需要跨项目查看同一租户内的执行状态。
     * 但它仍然不能突破租户边界，所以数据范围应为 TENANT，而不是 PLATFORM。</p>
     */
    @Test
    void operatorShouldDiagnoseQualityExecutionWithTenantScope() {
        PermissionDecisionRequest request = request(
                "OPERATOR",
                "GET",
                "/api/quality/quality-rules/executor/diagnostics",
                "QUALITY_EXECUTION",
                "DIAGNOSE"
        );
        when(querySupport.listRoutePolicies(10L, "OPERATOR"))
                .thenReturn(List.of(routePolicy(62002L, "OPERATOR", "GET",
                        "/api/quality/quality-rules/executor/diagnostics",
                        "QUALITY_EXECUTION", "DIAGNOSE", "ALLOW", 152,
                        "运营人员诊断质量执行器")));
        when(querySupport.listDataScopePolicies(10L, "OPERATOR", "QUALITY_EXECUTION"))
                .thenReturn(List.of(dataScope("OPERATOR", "QUALITY_EXECUTION",
                        "TENANT", "tenant_id = ${tenantId}")));

        PermissionDecisionResult result = decisionSupport.evaluate(request, "trace-quality-diagnostics");

        assertThat(result.getAllowed()).isTrue();
        assertThat(result.getMatchedRoutePolicyId()).isEqualTo(62002L);
        assertThat(result.getDataScopeLevel()).isEqualTo("TENANT");
        assertThat(result.getAuthorizedProjectIds()).isEmpty();
        assertThat(result.getReason()).contains("运营人员诊断质量执行器");
    }

    /**
     * 项目负责人不能伪造质量执行器回调。
     *
     * <p>项目负责人可以管理项目规则、触发检测或查看执行历史，但 worker 回调会直接改变执行事实。
     * 即使项目负责人有其他 QUALITY_EXECUTION 权限，也必须被更高优先级的 CALLBACK DENY 策略拦截。</p>
     */
    @Test
    void projectOwnerShouldDenyQualityExecutionCallbackEvenWhenOtherExecutionPolicyExists() {
        PermissionDecisionRequest request = request(
                "PROJECT_OWNER",
                "POST",
                "/api/quality/quality-rules/executor/executions/9001/succeed",
                "QUALITY_EXECUTION",
                "CALLBACK"
        );
        when(querySupport.listRoutePolicies(10L, "PROJECT_OWNER"))
                .thenReturn(List.of(
                        routePolicy(62003L, "PROJECT_OWNER", "POST",
                                "/api/quality/quality-rules/*/run-check",
                                "QUALITY_EXECUTION", "RUN", "ALLOW", 146,
                                "项目负责人触发质量检测"),
                        routePolicy(62004L, "PROJECT_OWNER", "POST",
                                "/api/quality/quality-rules/executor/executions/**",
                                "QUALITY_EXECUTION", "CALLBACK", "DENY", 1050,
                                "项目负责人禁止质量执行器回调")
                ));

        PermissionDecisionResult result = decisionSupport.evaluate(request, "trace-quality-callback-deny");

        assertThat(result.getAllowed()).isFalse();
        assertThat(result.getRouteEffect()).isEqualTo("DENY");
        assertThat(result.getMatchedRoutePolicyId()).isEqualTo(62004L);
        assertThat(result.getReason()).contains("显式拒绝策略");
    }

    /**
     * 服务账号可以提交质量执行器回调，并获得 TENANT 数据范围。
     *
     * <p>SERVICE_ACCOUNT 不是无限制超级账号。它只被允许调用明确的机器协议，并且仍然要带着租户范围。
     * data-quality 服务层随后还需要继续校验 execution 状态、执行器身份、幂等版本和回调顺序。</p>
     */
    @Test
    void serviceAccountShouldAllowQualityExecutionCallbackWithTenantScope() {
        PermissionDecisionRequest request = request(
                "SERVICE_ACCOUNT",
                "POST",
                "/api/quality/quality-rules/executor/executions/9001/succeed",
                "QUALITY_EXECUTION",
                "CALLBACK"
        );
        when(querySupport.listRoutePolicies(10L, "SERVICE_ACCOUNT"))
                .thenReturn(List.of(routePolicy(62005L, "SERVICE_ACCOUNT", "POST",
                        "/api/quality/quality-rules/executor/executions/**",
                        "QUALITY_EXECUTION", "CALLBACK", "ALLOW", 910,
                        "服务账号质量执行器回调")));
        when(querySupport.listDataScopePolicies(10L, "SERVICE_ACCOUNT", "QUALITY_EXECUTION"))
                .thenReturn(List.of(dataScope("SERVICE_ACCOUNT", "QUALITY_EXECUTION",
                        "TENANT", "tenant_id = ${tenantId}")));

        PermissionDecisionResult result = decisionSupport.evaluate(request, "trace-quality-callback-allow");

        assertThat(result.getAllowed()).isTrue();
        assertThat(result.getRouteEffect()).isEqualTo("ALLOW");
        assertThat(result.getDataScopeLevel()).isEqualTo("TENANT");
        assertThat(result.getReason()).contains("服务账号质量执行器回调");
    }

    /**
     * 构造权限判定请求。
     */
    private PermissionDecisionRequest request(String actorRole,
                                              String method,
                                              String path,
                                              String resourceType,
                                              String action) {
        PermissionDecisionRequest request = new PermissionDecisionRequest();
        request.setTenantId(10L);
        request.setActorId(1001L);
        request.setActorRole(actorRole);
        request.setHttpMethod(method);
        request.setRequestPath(path);
        request.setResourceType(resourceType);
        request.setAction(action);
        return request;
    }

    /**
     * 构造路由策略。
     *
     * <p>测试中显式传入 resourceType/action，是为了模拟 gateway 已经完成“路径到业务语义”的翻译。
     * permission-admin 不应该重新猜测业务语义，而应基于 gateway 传来的语义与策略矩阵做匹配。</p>
     */
    private PermissionRoutePolicy routePolicy(Long id,
                                              String roleCode,
                                              String method,
                                              String pathPattern,
                                              String resourceType,
                                              String action,
                                              String effect,
                                              int priority,
                                              String policyName) {
        PermissionRoutePolicy policy = new PermissionRoutePolicy();
        policy.setId(id);
        policy.setTenantId(0L);
        policy.setPolicyName(policyName);
        policy.setRoleCode(roleCode);
        policy.setHttpMethod(method);
        policy.setPathPattern(pathPattern);
        policy.setResourceType(resourceType);
        policy.setAction(action);
        policy.setEffect(effect);
        policy.setPriority(priority);
        policy.setEnabled(true);
        return policy;
    }

    /**
     * 构造数据范围策略。
     */
    private PermissionDataScopePolicy dataScope(String roleCode,
                                                String resourceType,
                                                String scopeLevel,
                                                String expression) {
        PermissionDataScopePolicy policy = new PermissionDataScopePolicy();
        policy.setTenantId(0L);
        policy.setRoleCode(roleCode);
        policy.setResourceType(resourceType);
        policy.setScopeLevel(scopeLevel);
        policy.setScopeExpression(expression);
        policy.setApprovalRequired(false);
        policy.setEnabled(true);
        return policy;
    }
}
