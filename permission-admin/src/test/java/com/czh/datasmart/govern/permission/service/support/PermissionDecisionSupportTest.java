/**
 * @Author : Cui
 * @Date: 2026/05/24 00:00
 * @Description DataSmart Govern Backend - PermissionDecisionSupportTest.java
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
 * 权限访问判定支持组件测试。
 *
 * <p>这里专门补 AgentPlan 接入口的权限语义：
 * gateway 会把 `/api/agent/plan-ingestions` 解释成 `AI_RUNTIME + INGEST_PLAN`，
 * permission-admin 必须能基于这个语义允许服务账号、拒绝普通用户，并写出正确数据范围。
 *
 * <p>该测试不启动 Spring 容器，也不依赖 MySQL 初始化脚本。
 * 原因是我们要保护的是判定算法：路由策略、资源类型、动作、数据范围如何组合成最终决策。
 */
class PermissionDecisionSupportTest {

    private PermissionQuerySupport querySupport;
    private PermissionDecisionSupport decisionSupport;

    @BeforeEach
    void setUp() {
        querySupport = mock(PermissionQuerySupport.class);
        PermissionAuditSupport auditSupport = mock(PermissionAuditSupport.class);
        decisionSupport = new PermissionDecisionSupport(querySupport, auditSupport);
    }

    /**
     * 验证服务账号可以接入 AgentPlan，并获得 AI_RUNTIME 的租户级数据范围。
     *
     * <p>服务账号不是“无限权限账号”。
     * 它被允许调用内部协议，但数据范围仍然应该被限制在当前租户，后续 Java agent-runtime 和业务工具执行器继续继承该边界。
     */
    @Test
    void serviceAccountShouldAllowAgentPlanIngestionWithTenantScope() {
        PermissionDecisionRequest request = decisionRequest("SERVICE_ACCOUNT");
        when(querySupport.listRoutePolicies(10L, "SERVICE_ACCOUNT"))
                .thenReturn(List.of(routePolicy("SERVICE_ACCOUNT", "ALLOW", 820)));
        when(querySupport.listDataScopePolicies(10L, "SERVICE_ACCOUNT", "AI_RUNTIME"))
                .thenReturn(List.of(dataScope("SERVICE_ACCOUNT", "AI_RUNTIME", "TENANT", "tenant_id = ${tenantId}")));

        PermissionDecisionResult result = decisionSupport.evaluate(request, "trace-agent-plan");

        assertThat(result.getAllowed()).isTrue();
        assertThat(result.getRouteEffect()).isEqualTo("ALLOW");
        assertThat(result.getDataScopeLevel()).isEqualTo("TENANT");
        assertThat(result.getDataScopeExpression()).isEqualTo("tenant_id = ${tenantId}");
        assertThat(result.getReason()).contains("服务账号接入 AgentPlan");
    }

    /**
     * 验证普通用户命中显式 DENY，不能直接伪造 Python AgentPlan。
     *
     * <p>普通用户应该通过产品会话入口表达目标，由智能网关和 Python Runtime 生成计划；
     * 如果允许用户直接 POST AgentPlan，等于让用户自行声明模型网关、工具参数和治理提示，风险过高。
     */
    @Test
    void ordinaryUserShouldDenyDirectAgentPlanIngestion() {
        PermissionDecisionRequest request = decisionRequest("ORDINARY_USER");
        when(querySupport.listRoutePolicies(10L, "ORDINARY_USER"))
                .thenReturn(List.of(routePolicy("ORDINARY_USER", "DENY", 830)));

        PermissionDecisionResult result = decisionSupport.evaluate(request, "trace-agent-plan-deny");

        assertThat(result.getAllowed()).isFalse();
        assertThat(result.getRouteEffect()).isEqualTo("DENY");
        assertThat(result.getReason()).contains("显式拒绝");
    }

    private PermissionDecisionRequest decisionRequest(String actorRole) {
        PermissionDecisionRequest request = new PermissionDecisionRequest();
        request.setTenantId(10L);
        request.setActorId(1001L);
        request.setActorRole(actorRole);
        request.setHttpMethod("POST");
        request.setRequestPath("/api/agent/plan-ingestions");
        request.setResourceType("AI_RUNTIME");
        request.setAction("INGEST_PLAN");
        return request;
    }

    private PermissionRoutePolicy routePolicy(String roleCode, String effect, int priority) {
        PermissionRoutePolicy policy = new PermissionRoutePolicy();
        policy.setId((long) priority);
        policy.setTenantId(0L);
        policy.setPolicyName("服务账号接入 AgentPlan");
        policy.setRoleCode(roleCode);
        policy.setHttpMethod("POST");
        policy.setPathPattern("/api/agent/plan-ingestions");
        policy.setResourceType("AI_RUNTIME");
        policy.setAction("INGEST_PLAN");
        policy.setEffect(effect);
        policy.setPriority(priority);
        policy.setEnabled(true);
        return policy;
    }

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
