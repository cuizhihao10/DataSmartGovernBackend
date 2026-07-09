/**
 * @Author : Cui
 * @Date: 2026/05/24 00:00
 * @Description DataSmart Govern Backend - PermissionDecisionSupportTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service.support;

import com.czh.datasmart.govern.common.context.PlatformAuthorizedProjectRole;
import com.czh.datasmart.govern.permission.controller.dto.PermissionDecisionRequest;
import com.czh.datasmart.govern.permission.controller.dto.PermissionDecisionResult;
import com.czh.datasmart.govern.permission.entity.PermissionDataScopePolicy;
import com.czh.datasmart.govern.permission.entity.PermissionRoutePolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
    private PermissionAuditSupport auditSupport;
    private PermissionDecisionSupport decisionSupport;

    @BeforeEach
    void setUp() {
        querySupport = mock(PermissionQuerySupport.class);
        auditSupport = mock(PermissionAuditSupport.class);
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
     * 验证 SERVICE_ACCOUNT 代表真实用户执行 Agent 高风险动作时，权限中心会返回策略版本和委托证据。
     *
     * <p>这条用例保护 4.67 的核心商业化语义：服务账号不是超级管理员，它仍然必须命中 route policy；
     * 但一旦它代表某个用户推进异步工具入箱，判定结果和审计记录都要能说明“谁代表谁、为什么、命中了哪条策略”。
     * 未来接入 selected-node outbox dispatcher、审批台或审计中心时，就可以把这份 evidence 作为责任链证据。</p>
     */
    @Test
    void delegatedServiceAccountShouldReturnPolicyVersionAndEvidence() {
        PermissionDecisionRequest request = decisionRequest("SERVICE_ACCOUNT");
        request.setRequestPath("/api/agent/sessions/session-1/runs/run-1/tool-executions/dag-selected-node-outbox/enqueue");
        request.setAction("ENQUEUE_SELECTED_ASYNC_TOOL");
        request.setServiceAccountActorId(900001L);
        request.setServiceAccountCode("datasmart-agent-runtime");
        request.setRepresentedActorId("actor-preview");
        request.setDelegationType("SERVICE_ACCOUNT_ON_BEHALF_OF_ACTOR");
        request.setDelegationReason("AGENT_RUNTIME_TOOL_PREVIEW:tool=data-sync.execute");
        when(querySupport.listRoutePolicies(10L, "SERVICE_ACCOUNT"))
                .thenReturn(List.of(selectedNodePolicy("ALLOW", 860)));
        when(querySupport.listDataScopePolicies(10L, "SERVICE_ACCOUNT", "AI_RUNTIME"))
                .thenReturn(List.of(dataScope("SERVICE_ACCOUNT", "AI_RUNTIME", "TENANT", "tenant_id = ${tenantId}")));

        PermissionDecisionResult result = decisionSupport.evaluate(request, "trace-selected-node");

        assertThat(result.getAllowed()).isTrue();
        assertThat(result.getDelegated()).isTrue();
        assertThat(result.getPolicyVersion()).contains("route-policy:860");
        assertThat(result.getDelegationEvidence())
                .contains("datasmart-agent-runtime")
                .contains("actor-preview")
                .contains("ENQUEUE_SELECTED_ASYNC_TOOL");
        ArgumentCaptor<PermissionDecisionResult> resultCaptor = ArgumentCaptor.forClass(PermissionDecisionResult.class);
        verify(auditSupport).saveDecisionAudit(org.mockito.ArgumentMatchers.eq(request),
                org.mockito.ArgumentMatchers.eq("trace-selected-node"),
                resultCaptor.capture());
        assertThat(resultCaptor.getValue().getDelegationEvidence()).isEqualTo(result.getDelegationEvidence());
    }

    /**
     * 验证 task-management worker 执行已确认异步工具时使用独立动作授权。
     *
     * <p>这条用例保护 4.77 的关键边界：selected-node 入箱和 worker 执行副作用不是同一个权限动作。
     * worker 执行前应以 SERVICE_ACCOUNT 身份代表上游 actor 重新 evaluate，并拿到新的 policyVersion 与委托证据。
     * 这样即使某个 command 已经进入任务中心，权限中心仍然可以在执行前收紧或撤销策略。</p>
     */
    @Test
    void serviceAccountShouldAllowConfirmedAsyncToolWorkerExecution() {
        PermissionDecisionRequest request = decisionRequest("SERVICE_ACCOUNT");
        request.setHttpMethod("POST");
        request.setRequestPath("/internal/task-management/agent-async-tools/audit-001/execute");
        request.setAction("EXECUTE_CONFIRMED_ASYNC_TOOL");
        request.setServiceAccountActorId(900002L);
        request.setServiceAccountCode("datasmart-task-management-agent-worker");
        request.setRepresentedActorId("actor-preview");
        request.setDelegationType("SERVICE_ACCOUNT_ON_BEHALF_OF_ACTOR");
        request.setDelegationReason("TASK_MANAGEMENT_AGENT_WORKER_EXECUTE:tool=data-sync.execute");
        when(querySupport.listRoutePolicies(10L, "SERVICE_ACCOUNT"))
                .thenReturn(List.of(workerExecutionPolicy("ALLOW", 870)));
        when(querySupport.listDataScopePolicies(10L, "SERVICE_ACCOUNT", "AI_RUNTIME"))
                .thenReturn(List.of(dataScope("SERVICE_ACCOUNT", "AI_RUNTIME", "TENANT", "tenant_id = ${tenantId}")));

        PermissionDecisionResult result = decisionSupport.evaluate(request, "trace-worker-execute");

        assertThat(result.getAllowed()).isTrue();
        assertThat(result.getPolicyVersion()).contains("route-policy:870");
        assertThat(result.getDelegated()).isTrue();
        assertThat(result.getDelegationEvidence())
                .contains("datasmart-task-management-agent-worker")
                .contains("actor-preview")
                .contains("EXECUTE_CONFIRMED_ASYNC_TOOL");
    }

    /**
     * 验证确认记录查询使用独立的 VIEW_TOOL_CONFIRMATIONS 动作。
     *
     * <p>confirmation 不是普通 Agent 会话详情，也不是 runtime event 时间线本身。
     * 它是用户确认、策略版本、服务账号委托和 outbox 入箱之间的证据链，所以 permission-admin 必须能按独立动作授权。
     */
    @Test
    void auditorShouldAllowViewingToolConfirmationsWithDedicatedAction() {
        PermissionDecisionRequest request = decisionRequest("AUDITOR");
        request.setHttpMethod("GET");
        request.setRequestPath("/api/agent/sessions/session-1/runs/run-1/tool-executions/dag-confirmations/confirmation-1");
        request.setResourceType("AI_RUNTIME");
        request.setAction("VIEW_TOOL_CONFIRMATIONS");
        when(querySupport.listRoutePolicies(10L, "AUDITOR"))
                .thenReturn(List.of(confirmationViewPolicy("AUDITOR", "ALLOW", 114)));
        when(querySupport.listDataScopePolicies(10L, "AUDITOR", "AI_RUNTIME"))
                .thenReturn(List.of(dataScope("AUDITOR", "AI_RUNTIME", "TENANT", "tenant_id = ${tenantId}")));

        PermissionDecisionResult result = decisionSupport.evaluate(request, "trace-confirmation-view");

        assertThat(result.getAllowed()).isTrue();
        assertThat(result.getMatchedRoutePolicyId()).isEqualTo(114L);
        assertThat(result.getDataScopeLevel()).isEqualTo("TENANT");
        assertThat(result.getReason()).contains("确认记录");
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

    /**
     * 验证 PROJECT 数据范围会返回项目 ID 与项目角色双重快照。
     *
     * <p>这条测试保护本轮权限体系收敛的核心语义：
     * gateway 和业务服务不能只知道“用户属于项目 101”，还必须知道“用户在项目 101 是 MANAGER 还是 READER”。
     * 否则只读用户可以绕过前端按钮隐藏，直接调用创建、编辑或授权接口。</p>
     */
    @Test
    void projectScopeShouldReturnProjectIdsAndProjectRoles() {
        PermissionDecisionRequest request = decisionRequest("PROJECT_OWNER");
        request.setRequestPath("/api/datasource/datasources");
        request.setResourceType("DATASOURCE");
        request.setAction("CREATE");
        when(querySupport.listRoutePolicies(10L, "PROJECT_OWNER"))
                .thenReturn(List.of(datasourceCreatePolicy("ALLOW", 150)));
        when(querySupport.listDataScopePolicies(10L, "PROJECT_OWNER", "DATASOURCE"))
                .thenReturn(List.of(dataScope("PROJECT_OWNER", "DATASOURCE", "PROJECT", "project_id IN ${actorProjectIds}")));
        when(querySupport.listActorProjectRoles(10L, 1001L))
                .thenReturn(List.of(
                        new PlatformAuthorizedProjectRole(101L, "OWNER"),
                        new PlatformAuthorizedProjectRole(205L, "MANAGER")));

        PermissionDecisionResult result = decisionSupport.evaluate(request, "trace-project-role");

        assertThat(result.getAllowed()).isTrue();
        assertThat(result.getAuthorizedProjectIds()).containsExactly(101L, 205L);
        assertThat(result.getAuthorizedProjectRoles())
                .extracting(PlatformAuthorizedProjectRole::projectRole)
                .containsExactly("OWNER", "MANAGER");
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

    private PermissionRoutePolicy selectedNodePolicy(String effect, int priority) {
        PermissionRoutePolicy policy = routePolicy("SERVICE_ACCOUNT", effect, priority);
        policy.setPolicyName("服务账号确认 DAG 选中节点异步入箱");
        policy.setPathPattern("/api/agent/sessions/*/runs/*/tool-executions/dag-selected-node-outbox/enqueue");
        policy.setAction("ENQUEUE_SELECTED_ASYNC_TOOL");
        return policy;
    }

    private PermissionRoutePolicy confirmationViewPolicy(String roleCode, String effect, int priority) {
        PermissionRoutePolicy policy = routePolicy(roleCode, effect, priority);
        policy.setPolicyName("审计员查看 Agent DAG 确认记录");
        policy.setHttpMethod("GET");
        policy.setPathPattern("/api/agent/sessions/*/runs/*/tool-executions/dag-confirmations/**");
        policy.setAction("VIEW_TOOL_CONFIRMATIONS");
        return policy;
    }

    private PermissionRoutePolicy workerExecutionPolicy(String effect, int priority) {
        PermissionRoutePolicy policy = routePolicy("SERVICE_ACCOUNT", effect, priority);
        policy.setPolicyName("服务账号执行已确认 Agent 异步工具");
        policy.setPathPattern("/internal/task-management/agent-async-tools/*/execute");
        policy.setAction("EXECUTE_CONFIRMED_ASYNC_TOOL");
        return policy;
    }

    private PermissionRoutePolicy datasourceCreatePolicy(String effect, int priority) {
        PermissionRoutePolicy policy = routePolicy("PROJECT_OWNER", effect, priority);
        policy.setPolicyName("项目负责人创建数据源");
        policy.setPathPattern("/api/datasource/datasources");
        policy.setResourceType("DATASOURCE");
        policy.setAction("CREATE");
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
