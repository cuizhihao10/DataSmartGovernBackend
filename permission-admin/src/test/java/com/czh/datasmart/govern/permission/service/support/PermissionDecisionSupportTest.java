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
import com.czh.datasmart.govern.permission.entity.PermissionProject;
import com.czh.datasmart.govern.permission.entity.PermissionRoutePolicy;
import com.czh.datasmart.govern.permission.entity.PermissionTenant;
import com.czh.datasmart.govern.permission.mapper.PermissionProjectMapper;
import com.czh.datasmart.govern.permission.mapper.PermissionTenantMapper;
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
    private PermissionTenantMapper tenantMapper;
    private PermissionProjectMapper projectMapper;
    private PermissionDecisionSupport decisionSupport;

    @BeforeEach
    void setUp() {
        querySupport = mock(PermissionQuerySupport.class);
        auditSupport = mock(PermissionAuditSupport.class);
        tenantMapper = mock(PermissionTenantMapper.class);
        projectMapper = mock(PermissionProjectMapper.class);
        when(tenantMapper.selectById(10L)).thenReturn(activeTenant());
        decisionSupport = new PermissionDecisionSupport(querySupport, auditSupport, tenantMapper, projectMapper);
    }

    @Test
    void suspendedTenantShouldBeDeniedBeforeRoutePolicyEvaluation() {
        PermissionTenant tenant = activeTenant();
        tenant.setStatus("SUSPENDED");
        when(tenantMapper.selectById(10L)).thenReturn(tenant);

        PermissionDecisionResult result = decisionSupport.evaluate(
                decisionRequest("ORDINARY_USER"), "trace-suspended-tenant");

        assertThat(result.getAllowed()).isFalse();
        assertThat(result.getReason()).contains("SUSPENDED");
    }

    private PermissionTenant activeTenant() {
        PermissionTenant tenant = new PermissionTenant();
        tenant.setTenantId(10L);
        tenant.setStatus("ACTIVE");
        return tenant;
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
     * 普通用户查询专业 Agent session 事实时必须命中 AI_RUNTIME + VIEW，而不是通用 CREATE/EXECUTE。
     *
     * <p>这个测试模拟 Flyway V48 落库后的核心判定结果。数据范围仍然是 SELF，且项目成员快照会被
     * 物化给 Gateway；agent-runtime 会继续用事实表中的 userId 做第二次对象归属过滤。</p>
     */
    @Test
    void ordinaryUserShouldViewOwnSpecialistSessionFactsInsideSelfScope() {
        PermissionDecisionRequest request = decisionRequest("ORDINARY_USER");
        request.setHttpMethod("GET");
        request.setRequestPath("/api/agent/specialist-turn-facts/sessions/session-1");
        request.setAction("VIEW");
        when(querySupport.listRoutePolicies(10L, "ORDINARY_USER"))
                .thenReturn(List.of(specialistFactViewPolicy(
                        "ORDINARY_USER",
                        "/api/agent/specialist-turn-facts/sessions/*",
                        978)));
        when(querySupport.listDataScopePolicies(10L, "ORDINARY_USER", "AI_RUNTIME"))
                .thenReturn(List.of(dataScope(
                        "ORDINARY_USER",
                        "AI_RUNTIME",
                        "SELF",
                        "actor_id = ${actorId} AND project_id IN ${actorProjectIds}")));
        when(querySupport.listActorProjectRoles(10L, 1001L))
                .thenReturn(List.of(new PlatformAuthorizedProjectRole(101L, "READER")));

        PermissionDecisionResult result = decisionSupport.evaluate(request, "trace-specialist-session-view");

        assertThat(result.getAllowed()).isTrue();
        assertThat(result.getMatchedRoutePolicyId()).isEqualTo(978L);
        assertThat(result.getDataScopeLevel()).isEqualTo("SELF");
        assertThat(result.getDataScopeExpression())
                .isEqualTo("actor_id = ${actorId} AND project_id IN ${actorProjectIds}");
        assertThat(result.getAuthorizedProjectIds()).containsExactly(101L);
    }

    /** run 查询与 session 查询必须使用同一 VIEW 语义，不能因为定位符不同退化成另一套范围。 */
    @Test
    void projectOwnerShouldViewOwnSpecialistRunFactsInsideSelfScope() {
        PermissionDecisionRequest request = decisionRequest("PROJECT_OWNER");
        request.setHttpMethod("GET");
        request.setRequestPath("/api/agent/specialist-turn-facts/runs/run-1");
        request.setAction("VIEW");
        when(querySupport.listRoutePolicies(10L, "PROJECT_OWNER"))
                .thenReturn(List.of(specialistFactViewPolicy(
                        "PROJECT_OWNER",
                        "/api/agent/specialist-turn-facts/runs/*",
                        988)));
        when(querySupport.listDataScopePolicies(10L, "PROJECT_OWNER", "AI_RUNTIME"))
                .thenReturn(List.of(dataScope(
                        "PROJECT_OWNER",
                        "AI_RUNTIME",
                        "SELF",
                        "actor_id = ${actorId} AND project_id IN ${actorProjectIds}")));
        when(querySupport.listActorProjectRoles(10L, 1001L))
                .thenReturn(List.of(new PlatformAuthorizedProjectRole(101L, "OWNER")));

        PermissionDecisionResult result = decisionSupport.evaluate(request, "trace-specialist-run-view");

        assertThat(result.getAllowed()).isTrue();
        assertThat(result.getMatchedRoutePolicyId()).isEqualTo(988L);
        assertThat(result.getDataScopeLevel()).isEqualTo("SELF");
        assertThat(result.getAuthorizedProjectIds()).containsExactly(101L);
    }

    /**
     * 普通用户即使把专业事实 POST 的请求动作伪装成 EXECUTE，也必须命中 V48 的显式 DENY。
     *
     * <p>这条用例和 agent-runtime 的 token 守卫互补：权限中心先拒绝人类主体，服务内部即使绕过
     * Gateway 访问 Java Controller，也还要通过 source-service + internal token 才能登记。</p>
     */
    @Test
    void ordinaryUserShouldNotRegisterSpecialistTurnFacts() {
        PermissionDecisionRequest request = decisionRequest("ORDINARY_USER");
        request.setRequestPath("/api/agent/specialist-turn-facts");
        request.setAction("EXECUTE");
        when(querySupport.listRoutePolicies(10L, "ORDINARY_USER"))
                .thenReturn(List.of(specialistFactRegistrationPolicy(
                        "ORDINARY_USER", "DENY", 1100,
                        "/api/agent/specialist-turn-facts/**")));

        PermissionDecisionResult result = decisionSupport.evaluate(request, "trace-specialist-register-deny");

        assertThat(result.getAllowed()).isFalse();
        assertThat(result.getRouteEffect()).isEqualTo("DENY");
        assertThat(result.getReason()).contains("显式拒绝");
    }

    /**
     * V48 的人类主体 DENY 必须压过未来可能新增的、更高优先级通用 Agent ALLOW。
     *
     * <p>权限系统的默认拒绝只能保护“没有规则”的今天，无法保护未来有人为了开放普通 Agent 功能新增
     * {@code /api/agent/**} 的场景。这里同时放入优先级更高的通用允许和 V48 专用拒绝，固定
     * deny-overrides 的 fail-closed 语义，防止普通用户伪造专业 Agent 低敏事实。</p>
     */
    @Test
    void humanSpecialistFactDenyShouldOverrideGenericAgentAllow() {
        PermissionDecisionRequest request = decisionRequest("ORDINARY_USER");
        request.setRequestPath("/api/agent/specialist-turn-facts");
        request.setAction("EXECUTE");
        PermissionRoutePolicy genericAgentAllow = specialistFactRegistrationPolicy(
                "ORDINARY_USER", "ALLOW", 1200, "/api/agent/**");
        genericAgentAllow.setPolicyName("普通 Agent 通用执行入口");
        PermissionRoutePolicy specialistDeny = specialistFactRegistrationPolicy(
                "ORDINARY_USER", "DENY", 1100, "/api/agent/specialist-turn-facts/**");
        when(querySupport.listRoutePolicies(10L, "ORDINARY_USER"))
                .thenReturn(List.of(genericAgentAllow, specialistDeny));

        PermissionDecisionResult result = decisionSupport.evaluate(request, "trace-specialist-deny-priority");

        assertThat(result.getAllowed()).isFalse();
        assertThat(result.getRouteEffect()).isEqualTo("DENY");
        assertThat(result.getMatchedRoutePolicyId()).isEqualTo(1100L);
    }

    /**
     * 受信服务账号登记事实时使用独立 EXECUTE 动作，并继承已有 AI_RUNTIME 租户范围。
     *
     * <p>此处只验证权限中心策略，不把“命中 SERVICE_ACCOUNT”当作事实写入成功的充分条件；
     * Controller 仍要求共享 token，事实本身仍要求完整 tenant/project/user 责任链。</p>
     */
    @Test
    void trustedServiceAccountShouldExecuteSpecialistTurnFactRegistration() {
        PermissionDecisionRequest request = decisionRequest("SERVICE_ACCOUNT");
        request.setRequestPath("/api/agent/specialist-turn-facts");
        request.setAction("EXECUTE");
        when(querySupport.listRoutePolicies(10L, "SERVICE_ACCOUNT"))
                .thenReturn(List.of(specialistFactRegistrationPolicy(
                        "SERVICE_ACCOUNT", "ALLOW", 989,
                        "/api/agent/specialist-turn-facts")));
        when(querySupport.listDataScopePolicies(10L, "SERVICE_ACCOUNT", "AI_RUNTIME"))
                .thenReturn(List.of(dataScope(
                        "SERVICE_ACCOUNT",
                        "AI_RUNTIME",
                        "TENANT",
                        "tenant_id = ${tenantId}")));

        PermissionDecisionResult result = decisionSupport.evaluate(request, "trace-specialist-register-service");

        assertThat(result.getAllowed()).isTrue();
        assertThat(result.getRouteEffect()).isEqualTo("ALLOW");
        assertThat(result.getDataScopeLevel()).isEqualTo("TENANT");
        assertThat(result.getReason()).contains("专业 Agent");
    }

    /**
     * 租户管理员、审计员和运营人员目前没有“用户 Agent session/run 详情”专用 VIEW 策略，
     * 因此访问该敏感事实入口应保持默认拒绝，而不是因为角色名字看起来较高就自动获得跨用户事实。
     *
     * <p>这些角色已有的 Agent 运行事件/诊断入口不受本迁移影响；如果未来要开放项目级或租户级事实审计，
     * 应新增独立的 AUDIT/DIAGNOSE 路由和对应数据范围，不应复用普通用户 session/run 入口。</p>
     */
    @Test
    void administrativeRolesWithoutDedicatedSpecialistFactViewShouldRemainDenied() {
        for (String role : List.of("TENANT_ADMINISTRATOR", "AUDITOR", "OPERATOR")) {
            PermissionDecisionRequest request = decisionRequest(role);
            request.setHttpMethod("GET");
            request.setRequestPath("/api/agent/specialist-turn-facts/runs/run-1");
            request.setAction("VIEW");
            when(querySupport.listRoutePolicies(10L, role)).thenReturn(List.of());

            PermissionDecisionResult result = decisionSupport.evaluate(
                    request, "trace-specialist-admin-deny-" + role);

            assertThat(result.getAllowed()).as("role=%s", role).isFalse();
            assertThat(result.getReason()).as("role=%s", role).contains("没有命中任何启用的路由策略");
        }
    }

    /**
     * 审计员和运营员已有的 Agent 运行事件入口不能因为 V48 的专业事实 metadata 而被误判为无权。
     *
     * <p>两条入口故意使用历史动作 VIEW_EVENTS/DIAGNOSE，而不是把它们改写为专业事实 VIEW；
     * 这样既保持原有审计/运维语义，也避免通过旧入口扩大专业 turn 事实的对象范围。</p>
     */
    @Test
    void existingAuditorAndOperatorAgentEventQueriesShouldRemainAllowed() {
        PermissionRoutePolicy auditorPolicy = routePolicy("AUDITOR", "ALLOW", 113);
        auditorPolicy.setHttpMethod("GET");
        auditorPolicy.setPathPattern("/api/agent/runtime-events/**");
        auditorPolicy.setAction("VIEW_EVENTS");
        auditorPolicy.setPolicyName("审计员查看 Agent 运行事件");

        PermissionDecisionRequest auditorRequest = decisionRequest("AUDITOR");
        auditorRequest.setHttpMethod("GET");
        auditorRequest.setRequestPath("/api/agent/runtime-events/run-1");
        auditorRequest.setAction("VIEW_EVENTS");
        when(querySupport.listRoutePolicies(10L, "AUDITOR")).thenReturn(List.of(auditorPolicy));

        PermissionDecisionResult auditorResult = decisionSupport.evaluate(
                auditorRequest, "trace-existing-auditor-agent-events");

        assertThat(auditorResult.getAllowed()).isTrue();
        assertThat(auditorResult.getMatchedRoutePolicyId()).isEqualTo(113L);

        PermissionRoutePolicy operatorPolicy = routePolicy("OPERATOR", "ALLOW", 137);
        operatorPolicy.setHttpMethod("GET");
        operatorPolicy.setPathPattern("/api/agent/runtime-events/**");
        operatorPolicy.setAction("DIAGNOSE");
        operatorPolicy.setPolicyName("运营人员诊断 Agent 运行事件");

        PermissionDecisionRequest operatorRequest = decisionRequest("OPERATOR");
        operatorRequest.setHttpMethod("GET");
        operatorRequest.setRequestPath("/api/agent/runtime-events/diagnostics");
        operatorRequest.setAction("DIAGNOSE");
        when(querySupport.listRoutePolicies(10L, "OPERATOR")).thenReturn(List.of(operatorPolicy));

        PermissionDecisionResult operatorResult = decisionSupport.evaluate(
                operatorRequest, "trace-existing-operator-agent-events");

        assertThat(operatorResult.getAllowed()).isTrue();
        assertThat(operatorResult.getMatchedRoutePolicyId()).isEqualTo(137L);
    }

    /**
     * 平台管理员已有平台级 GET 兜底策略，新增专用 Gateway metadata 不应改变这条既有语义。
     *
     * <p>但因为没有显式 AI_RUNTIME 数据范围，这里只能得到“路由允许、范围未扩大”的结果；
     * agent-runtime 收到空范围后会退回当前 actor SELF 查询，不能仅凭 PLATFORM_ADMINISTRATOR
     * 角色直接读取其他项目或其他租户的专业事实。</p>
     */
    @Test
    void platformAdministratorGenericAgentViewShouldRemainAllowedWithoutExpandingObjectScope() {
        PermissionDecisionRequest request = decisionRequest("PLATFORM_ADMINISTRATOR");
        request.setHttpMethod("GET");
        request.setRequestPath("/api/agent/specialist-turn-facts/sessions/session-1");
        request.setAction("VIEW");

        PermissionRoutePolicy platformFallback = new PermissionRoutePolicy();
        platformFallback.setId(1000L);
        platformFallback.setTenantId(0L);
        platformFallback.setPolicyName("平台管理员全平台权限");
        platformFallback.setRoleCode("PLATFORM_ADMINISTRATOR");
        platformFallback.setHttpMethod("ANY");
        platformFallback.setPathPattern("/api/**");
        platformFallback.setEffect("ALLOW");
        platformFallback.setPriority(1000);
        platformFallback.setEnabled(true);
        when(querySupport.listRoutePolicies(10L, "PLATFORM_ADMINISTRATOR"))
                .thenReturn(List.of(platformFallback));
        when(querySupport.listDataScopePolicies(10L, "PLATFORM_ADMINISTRATOR", "AI_RUNTIME"))
                .thenReturn(List.of());

        PermissionDecisionResult result = decisionSupport.evaluate(request, "trace-specialist-platform-view");

        assertThat(result.getAllowed()).isTrue();
        assertThat(result.getDataScopeLevel()).isNull();
        assertThat(result.getAuthorizedProjectIds()).isEmpty();
    }

    /** 平台管理员原有 Agent runtime-events GET 兜底仍可命中，专用事实路由不会把通用查询误拒绝。 */
    @Test
    void platformAdministratorExistingGenericAgentQueryShouldRemainAllowed() {
        PermissionDecisionRequest request = decisionRequest("PLATFORM_ADMINISTRATOR");
        request.setHttpMethod("GET");
        request.setRequestPath("/api/agent/runtime-events/run-1");
        request.setAction("VIEW_EVENTS");

        PermissionRoutePolicy platformFallback = new PermissionRoutePolicy();
        platformFallback.setId(1000L);
        platformFallback.setTenantId(0L);
        platformFallback.setPolicyName("平台管理员全平台权限");
        platformFallback.setRoleCode("PLATFORM_ADMINISTRATOR");
        platformFallback.setHttpMethod("ANY");
        platformFallback.setPathPattern("/api/**");
        platformFallback.setEffect("ALLOW");
        platformFallback.setPriority(1000);
        platformFallback.setEnabled(true);
        when(querySupport.listRoutePolicies(10L, "PLATFORM_ADMINISTRATOR"))
                .thenReturn(List.of(platformFallback));
        when(querySupport.listDataScopePolicies(10L, "PLATFORM_ADMINISTRATOR", "AI_RUNTIME"))
                .thenReturn(List.of());

        PermissionDecisionResult result = decisionSupport.evaluate(
                request, "trace-platform-existing-agent-events");

        assertThat(result.getAllowed()).isTrue();
        assertThat(result.getDataScopeLevel()).isNull();
        assertThat(result.getAuthorizedProjectIds()).isEmpty();
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

    /**
     * SELF 只收口资源实例，不代表用户脱离项目边界。
     * gateway 必须继续拿到项目成员快照，才能把可信 projectId 和项目角色传给 datasource/data-sync。
     */
    @Test
    void selfScopeShouldReturnJoinedProjectIdsAndRoles() {
        PermissionDecisionRequest request = decisionRequest("ORDINARY_USER");
        request.setHttpMethod("GET");
        request.setRequestPath("/api/datasource/datasources");
        request.setResourceType("DATASOURCE");
        request.setAction("VIEW");
        when(querySupport.listRoutePolicies(10L, "ORDINARY_USER"))
                .thenReturn(List.of(datasourceListPolicy("ORDINARY_USER", "ALLOW", 151)));
        when(querySupport.listDataScopePolicies(10L, "ORDINARY_USER", "DATASOURCE"))
                .thenReturn(List.of(dataScope("ORDINARY_USER", "DATASOURCE", "SELF", "owner_id = ${actorId}")));
        when(querySupport.listActorProjectRoles(10L, 1001L))
                .thenReturn(List.of(new PlatformAuthorizedProjectRole(101L, "READER")));

        PermissionDecisionResult result = decisionSupport.evaluate(request, "trace-self-project-role");

        assertThat(result.getAllowed()).isTrue();
        assertThat(result.getDataScopeLevel()).isEqualTo("SELF");
        assertThat(result.getAuthorizedProjectIds()).containsExactly(101L);
        assertThat(result.getAuthorizedProjectRoles())
                .containsExactly(new PlatformAuthorizedProjectRole(101L, "READER"));
    }

    /**
     * 集合策略使用 / ** 后缀时必须同时覆盖集合根路径，否则列表页会被默认拒绝，
     * 但 /{id} 详情又能通过，形成同一资源前后不一致的权限体验。
     */
    @Test
    void wildcardCollectionPolicyShouldMatchCollectionRoot() {
        PermissionDecisionRequest request = decisionRequest("PROJECT_OWNER");
        request.setHttpMethod("GET");
        request.setRequestPath("/api/permission/project-memberships");
        request.setResourceType("PROJECT_MEMBERSHIP");
        request.setAction("VIEW");
        when(querySupport.listRoutePolicies(10L, "PROJECT_OWNER"))
                .thenReturn(List.of(projectMembershipPolicy("ALLOW", 152)));
        when(querySupport.listDataScopePolicies(10L, "PROJECT_OWNER", "PROJECT_MEMBERSHIP"))
                .thenReturn(List.of());

        PermissionDecisionResult result = decisionSupport.evaluate(request, "trace-membership-root");

        assertThat(result.getAllowed()).isTrue();
        assertThat(result.getMatchedRoutePolicyId()).isEqualTo(152L);
    }

    @Test
    void platformScopeShouldResolveEffectiveTenantFromSelectedProject() {
        PermissionDecisionRequest request = decisionRequest("PLATFORM_ADMINISTRATOR");
        request.setRequestedProjectId(101L);
        when(querySupport.listRoutePolicies(10L, "PLATFORM_ADMINISTRATOR"))
                .thenReturn(List.of(routePolicy("PLATFORM_ADMINISTRATOR", "ALLOW", 900)));
        when(querySupport.listDataScopePolicies(10L, "PLATFORM_ADMINISTRATOR", "AI_RUNTIME"))
                .thenReturn(List.of(dataScope("PLATFORM_ADMINISTRATOR", "AI_RUNTIME", "PLATFORM", "1 = 1")));
        PermissionProject project = new PermissionProject();
        project.setProjectId(101L);
        project.setTenantId(20L);
        project.setApplicationId(301L);
        project.setStatus("ACTIVE");
        when(projectMapper.selectById(101L)).thenReturn(project);

        PermissionDecisionResult result = decisionSupport.evaluate(request, "trace-platform-project");

        assertThat(result.getAllowed()).isTrue();
        assertThat(result.getEffectiveTenantId()).isEqualTo(20L);
        assertThat(result.getEffectiveApplicationId()).isEqualTo(301L);
    }

    @Test
    void tenantScopeShouldRejectProjectFromAnotherTenant() {
        PermissionDecisionRequest request = decisionRequest("TENANT_ADMINISTRATOR");
        request.setRequestedProjectId(101L);
        when(querySupport.listRoutePolicies(10L, "TENANT_ADMINISTRATOR"))
                .thenReturn(List.of(routePolicy("TENANT_ADMINISTRATOR", "ALLOW", 800)));
        when(querySupport.listDataScopePolicies(10L, "TENANT_ADMINISTRATOR", "AI_RUNTIME"))
                .thenReturn(List.of(dataScope("TENANT_ADMINISTRATOR", "AI_RUNTIME", "TENANT", "tenant_id = ${tenantId}")));
        PermissionProject project = new PermissionProject();
        project.setProjectId(101L);
        project.setTenantId(20L);
        project.setStatus("ACTIVE");
        when(projectMapper.selectById(101L)).thenReturn(project);

        PermissionDecisionResult result = decisionSupport.evaluate(request, "trace-tenant-project");

        assertThat(result.getAllowed()).isFalse();
        assertThat(result.getReason()).contains("不属于当前租户");
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

    /** 构造专业事实读取策略，统一固定 GET + AI_RUNTIME + VIEW 三个语义维度。 */
    private PermissionRoutePolicy specialistFactViewPolicy(String roleCode, String pathPattern, int priority) {
        PermissionRoutePolicy policy = routePolicy(roleCode, "ALLOW", priority);
        policy.setPolicyName("查看专业 Agent turn 事实");
        policy.setHttpMethod("GET");
        policy.setPathPattern(pathPattern);
        policy.setResourceType("AI_RUNTIME");
        policy.setAction("VIEW");
        return policy;
    }

    /** 构造专业事实登记策略，用于同时验证 SERVICE_ACCOUNT allow 和人类角色 deny。 */
    private PermissionRoutePolicy specialistFactRegistrationPolicy(String roleCode,
                                                                   String effect,
                                                                   int priority,
                                                                   String pathPattern) {
        PermissionRoutePolicy policy = routePolicy(roleCode, effect, priority);
        policy.setPolicyName("专业 Agent turn 事实登记策略");
        policy.setHttpMethod("POST");
        policy.setPathPattern(pathPattern);
        policy.setResourceType("AI_RUNTIME");
        policy.setAction("EXECUTE");
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

    private PermissionRoutePolicy datasourceListPolicy(String roleCode, String effect, int priority) {
        PermissionRoutePolicy policy = routePolicy(roleCode, effect, priority);
        policy.setPolicyName("普通用户查看可见数据源");
        policy.setHttpMethod("GET");
        policy.setPathPattern("/api/datasource/**");
        policy.setResourceType("DATASOURCE");
        policy.setAction("VIEW");
        return policy;
    }

    private PermissionRoutePolicy projectMembershipPolicy(String effect, int priority) {
        PermissionRoutePolicy policy = routePolicy("PROJECT_OWNER", effect, priority);
        policy.setPolicyName("项目负责人查看项目成员");
        policy.setHttpMethod("GET");
        policy.setPathPattern("/api/permission/project-memberships/**");
        policy.setResourceType("PROJECT_MEMBERSHIP");
        policy.setAction("VIEW");
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
