/**
 * @Author : Cui
 * @Date: 2026/08/05 00:00
 * @Description DataSmart Govern Backend - SpecialistTurnFactServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.specialist;

import com.czh.datasmart.govern.agent.service.session.AgentSessionAccessContext;
import com.czh.datasmart.govern.agent.config.AgentSessionTrustedAccessProperties;
import com.czh.datasmart.govern.common.context.PlatformAuthorizedProjectRole;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 专业 Agent turn 事实应用服务测试。
 *
 * <p>这里刻意让 mock Store 返回超出当前用户范围的记录，验证 Service 不会把“Store 忘记过滤”当成安全前提。
 * 这层二次过滤是未来切换缓存、读副本或事件投影 Store 时仍然必须保留的纵深防御。</p>
 */
class SpecialistTurnFactServiceTest {

    /** 普通用户只能看到同一租户、同一应用、同一项目、同一 actor 的事实。 */
    @Test
    void shouldFilterReturnedFactsByUserScopeAgain() {
        SpecialistTurnFactStore store = mock(SpecialistTurnFactStore.class);
        SpecialistTurnFactTrustedServiceGuard guard = mock(SpecialistTurnFactTrustedServiceGuard.class);
        SpecialistTurnFactService service = new SpecialistTurnFactService(store, guard);
        SpecialistTurnFact ownFact = fact("user-a", 10L, 20L, "turn-own");
        SpecialistTurnFact otherUser = fact("user-b", 10L, 20L, "turn-other");
        SpecialistTurnFact otherApplication = factForApplication("user-a", 10L, 10011L, 20L, "turn-application");
        SpecialistTurnFact otherProject = fact("user-a", 10L, 21L, "turn-project");
        SpecialistTurnFact otherTenant = fact("user-a", 11L, 20L, "turn-tenant");
        when(store.findBySession(any(), eq("session-a"), eq(100)))
                .thenReturn(List.of(ownFact, otherUser, otherApplication, otherProject, otherTenant));

        List<SpecialistTurnFact> result = service.findBySession(
                "session-a",
                new AgentSessionAccessContext(
                        10L, 10010L, 20L, "user-a", "ORDINARY_USER", "SELF", List.of(20L), List.of()),
                0
        );

        assertEquals(List.of(ownFact), result);
        verify(store).findBySession(SpecialistTurnFact.userScope(10L, 10010L, 20L, "user-a"), "session-a", 100);
    }

    /** 项目审计角色可以看当前项目内其他用户，但不能越过租户或项目边界。 */
    @Test
    void shouldKeepPrivilegedReadInsideCurrentTenantAndProject() {
        SpecialistTurnFactStore store = mock(SpecialistTurnFactStore.class);
        SpecialistTurnFactTrustedServiceGuard guard = mock(SpecialistTurnFactTrustedServiceGuard.class);
        SpecialistTurnFactService service = new SpecialistTurnFactService(store, guard);
        SpecialistTurnFact projectFact = fact("user-a", 10L, 20L, "turn-project");
        SpecialistTurnFact crossTenantFact = fact("user-a", 11L, 20L, "turn-tenant");
        when(store.findByRun(any(), eq("run-a"), eq(50)))
                .thenReturn(List.of(projectFact, crossTenantFact));

        List<SpecialistTurnFact> result = service.findByRun(
                "run-a",
                new AgentSessionAccessContext(
                        10L, 10010L, 20L, "auditor", "TENANT_ADMINISTRATOR", "TENANT", List.of(), List.of()),
                50
        );

        assertEquals(List.of(projectFact), result);
        verify(store).findByRun(SpecialistTurnFact.projectAuditScope(10L, 10010L, 20L), "run-a", 50);
    }

    /**
     * 管理员角色本身不能放大读取范围；缺少 permission-admin 的显式数据范围时只能退回自己的事实。
     */
    @Test
    void shouldRejectAdminReadWithoutExplicitDataScope() {
        SpecialistTurnFactStore store = mock(SpecialistTurnFactStore.class);
        SpecialistTurnFactTrustedServiceGuard guard = mock(SpecialistTurnFactTrustedServiceGuard.class);
        SpecialistTurnFactService service = new SpecialistTurnFactService(store, guard);

        assertThrows(PlatformBusinessException.class, () -> service.findBySession(
                "session-a", new AgentSessionAccessContext(10L, 20L, "tenant-admin", "TENANT_ADMINISTRATOR"), 100));
        verifyNoInteractions(store);
    }

    /** 项目范围快照没有当前项目时必须拒绝，不能仅凭 actorId 读取自己的事实。 */
    @Test
    void shouldRejectSelfReadWithoutCurrentProjectAuthorization() {
        SpecialistTurnFactStore store = mock(SpecialistTurnFactStore.class);
        SpecialistTurnFactService service = new SpecialistTurnFactService(
                store, mock(SpecialistTurnFactTrustedServiceGuard.class));

        assertThrows(PlatformBusinessException.class, () -> service.findBySession(
                "session-a",
                new AgentSessionAccessContext(
                        10L, 10010L, 20L, "user-a", "ORDINARY_USER", "SELF", List.of(21L), List.of()),
                100));
        verifyNoInteractions(store);
    }

    /** 普通用户不能伪造 TENANT 范围；范围值和角色必须由可信权限快照共同证明。 */
    @Test
    void shouldRejectForgedTenantScopeForOrdinaryUser() {
        SpecialistTurnFactStore store = mock(SpecialistTurnFactStore.class);
        SpecialistTurnFactService service = new SpecialistTurnFactService(
                store, mock(SpecialistTurnFactTrustedServiceGuard.class));

        assertThrows(PlatformBusinessException.class, () -> service.findByRun(
                "run-a",
                new AgentSessionAccessContext(
                        10L, 10010L, 20L, "user-a", "ORDINARY_USER", "TENANT", List.of(), List.of()),
                100));
        verifyNoInteractions(store);
    }

    /**
     * 项目 OWNER/MANAGER 的跨 actor 读取必须同时命中 PROJECT 范围和当前项目授权快照。
     */
    @Test
    void shouldAllowProjectManagerWithExplicitProjectScope() {
        SpecialistTurnFactStore store = mock(SpecialistTurnFactStore.class);
        SpecialistTurnFactTrustedServiceGuard guard = mock(SpecialistTurnFactTrustedServiceGuard.class);
        SpecialistTurnFactService service = new SpecialistTurnFactService(store, guard);
        SpecialistTurnFact projectFact = fact("user-b", 10L, 20L, "turn-project");
        SpecialistTurnFact crossProject = fact("user-b", 10L, 21L, "turn-cross-project");
        when(store.findByRun(any(), eq("run-a"), eq(50)))
                .thenReturn(List.of(projectFact, crossProject));

        AgentSessionAccessContext access = new AgentSessionAccessContext(
                10L,
                10010L,
                20L,
                "project-owner",
                "ORDINARY_USER",
                "PROJECT",
                List.of(20L),
                List.of(new PlatformAuthorizedProjectRole(20L, "MANAGER"))
        );

        List<SpecialistTurnFact> result = service.findByRun("run-a", access, 50);

        assertEquals(List.of(projectFact), result);
        verify(store).findByRun(SpecialistTurnFact.projectAuditScope(10L, 10010L, 20L), "run-a", 50);
    }

    /**
     * 平台管理员的 PLATFORM 数据范围允许当前目标租户/项目内跨 actor，但 Service 仍必须拒绝跨租户和跨项目记录。
     */
    @Test
    void shouldKeepPlatformAdminObjectCheckInsideCurrentTenantAndProject() {
        SpecialistTurnFactStore store = mock(SpecialistTurnFactStore.class);
        SpecialistTurnFactTrustedServiceGuard guard = mock(SpecialistTurnFactTrustedServiceGuard.class);
        SpecialistTurnFactService service = new SpecialistTurnFactService(store, guard);
        SpecialistTurnFact currentProject = fact("user-a", 10L, 20L, "turn-current");
        SpecialistTurnFact otherTenant = fact("user-a", 11L, 20L, "turn-other-tenant");
        SpecialistTurnFact otherProject = fact("user-a", 10L, 21L, "turn-other-project");
        when(store.findBySession(any(), eq("session-a"), eq(100)))
                .thenReturn(List.of(currentProject, otherTenant, otherProject));

        List<SpecialistTurnFact> result = service.findBySession(
                "session-a",
                new AgentSessionAccessContext(
                        10L, 10010L, 20L, "platform-admin", "PLATFORM_ADMINISTRATOR", "PLATFORM", List.of(), List.of()),
                100
        );

        assertEquals(List.of(currentProject), result);
        verify(store).findBySession(SpecialistTurnFact.projectAuditScope(10L, 10010L, 20L), "session-a", 100);
    }

    /** 缺少可信上下文时必须拒绝查询，不能因为 sessionId/runId 存在就退化成全表范围。 */
    @Test
    void shouldRejectQueryWithoutCompleteAccessContext() {
        SpecialistTurnFactService service = new SpecialistTurnFactService(
                mock(SpecialistTurnFactStore.class), mock(SpecialistTurnFactTrustedServiceGuard.class));

        assertThrows(PlatformBusinessException.class, () -> service.findBySession(
                "session-a", new AgentSessionAccessContext(null, 20L, "user-a", "ORDINARY_USER"), 10));
        assertThrows(PlatformBusinessException.class, () -> service.findByRun(
                "run-a", new AgentSessionAccessContext(10L, 20L, " ", "ORDINARY_USER"), 10));
    }

    /** 登记必须先通过可信服务守卫，再交给 Store 保存。 */
    @Test
    void shouldAuthenticateRegistrationBeforeSaving() {
        SpecialistTurnFactStore store = mock(SpecialistTurnFactStore.class);
        SpecialistTurnFactTrustedServiceGuard guard = mock(SpecialistTurnFactTrustedServiceGuard.class);
        SpecialistTurnFactService service = new SpecialistTurnFactService(store, guard);
        SpecialistTurnFact fact = fact("user-a", 10L, 20L, "turn-register");
        when(store.save(fact)).thenReturn(fact);

        assertEquals(fact, service.register(fact, "python-ai-runtime", "trusted-secret"));
        verify(guard).requireTrustedRegistration("python-ai-runtime", "trusted-secret");
        verify(store).save(fact);
    }

    /**
     * 浏览器即使把事实状态写成 APPROVED，也不能仅靠状态字段登记；source-service 和内部 token 必须同时可信。
     */
    @Test
    void shouldRejectBrowserForgedApprovedFactBeforeStore() {
        SpecialistTurnFactStore store = mock(SpecialistTurnFactStore.class);
        AgentSessionTrustedAccessProperties properties = new AgentSessionTrustedAccessProperties();
        properties.setSharedToken("trusted-secret");
        properties.setAllowedAutomatedExecutionSourceServices(java.util.Set.of("python-ai-runtime"));
        SpecialistTurnFactTrustedServiceGuard guard = new SpecialistTurnFactTrustedServiceGuard(properties);
        SpecialistTurnFactService service = new SpecialistTurnFactService(store, guard);

        assertThrows(PlatformBusinessException.class,
                () -> service.register(fact("user-a", 10L, 20L, "turn-approved", "APPROVED"),
                        "browser-client", "trusted-secret"));
        verifyNoInteractions(store);
    }

    /**
     * 后确认完成证据应接受同一父委托下、按 role/turn 分别派生的两个 Specialist 子委托。
     *
     * <p>PRECHECK 与 MONITOR 不能共用主 Agent delegation，也不会彼此共用子 delegation。测试使用
     * 跨语言派生合同的固定向量，避免 Service 退回到“所有事实 delegation 必须相同”的不可满足条件。</p>
     */
    @Test
    void shouldAcceptPerTurnChildDelegationsDerivedFromCurrentSessionParent() {
        SpecialistTurnFactStore store = mock(SpecialistTurnFactStore.class);
        SpecialistTurnFactService service = new SpecialistTurnFactService(
                store, mock(SpecialistTurnFactTrustedServiceGuard.class));
        SpecialistTurnFact precheck = postConfirmFact(
                "turn-precheck",
                "precheck-agent",
                "PRECHECK_AGENT",
                "delegation-b0ed04b3c646d34f2dfa034b");
        SpecialistTurnFact monitor = postConfirmFact(
                "turn-monitor",
                "monitor-agent",
                "MONITOR_AGENT",
                "delegation-cc311b0455798b69100d7b0e");
        when(store.findByRun(any(), eq("run-a"), eq(SpecialistTurnFact.MAX_QUERY_LIMIT)))
                .thenReturn(List.of(precheck, monitor));

        boolean complete = service.hasTerminalSuccessfulEvidenceForRoles(
                10L,
                10010L,
                20L,
                "user-a",
                "session-a",
                "run-a",
                "delegation-parent-1",
                List.of("PRECHECK_AGENT", "MONITOR_AGENT")
        );

        assertTrue(complete);
        verify(store).findByRun(
                SpecialistTurnFact.userScope(10L, 10010L, 20L, "user-a"),
                "run-a",
                SpecialistTurnFact.MAX_QUERY_LIMIT);
    }

    /**
     * 父委托原值或任意伪造子委托都不能作为 Specialist 完成证据。
     *
     * <p>事实登记接口的服务认证只能证明“来自 Python Runtime”，不能替代对象责任链校验。即使状态、角色、
     * 证据引用和所有租户范围都正确，只要 delegation 不是当前父委托与该 turn/role 的确定性派生值，
     * Java 仍必须 fail-closed。</p>
     */
    @Test
    void shouldRejectParentOrForgedDelegationAsSpecialistCompletionEvidence() {
        SpecialistTurnFactStore store = mock(SpecialistTurnFactStore.class);
        SpecialistTurnFactService service = new SpecialistTurnFactService(
                store, mock(SpecialistTurnFactTrustedServiceGuard.class));
        SpecialistTurnFact parentReused = postConfirmFact(
                "turn-precheck", "precheck-agent", "PRECHECK_AGENT", "delegation-parent-1");
        SpecialistTurnFact forged = postConfirmFact(
                "turn-monitor", "monitor-agent", "MONITOR_AGENT", "delegation-forged-value");
        when(store.findByRun(any(), eq("run-a"), eq(SpecialistTurnFact.MAX_QUERY_LIMIT)))
                .thenReturn(List.of(parentReused, forged));

        boolean complete = service.hasTerminalSuccessfulEvidenceForRoles(
                10L,
                10010L,
                20L,
                "user-a",
                "session-a",
                "run-a",
                "delegation-parent-1",
                List.of("PRECHECK_AGENT", "MONITOR_AGENT")
        );

        assertFalse(complete);
    }

    /** 构造一个最小合法事实。 */
    private SpecialistTurnFact fact(String userId, Long tenantId, Long projectId, String turnId) {
        return fact(userId, tenantId, projectId, turnId, "SUCCEEDED");
    }

    /** 构造指定状态事实，用于验证状态字段不能取代可信服务身份。 */
    private SpecialistTurnFact fact(String userId,
                                    Long tenantId,
                                    Long projectId,
                                    String turnId,
                                    String status) {
        Instant now = Instant.parse("2026-08-05T00:00:00Z");
        return new SpecialistTurnFact(
                userId, tenantId, 10010L, projectId, "session-a", "run-a", turnId, "idem-" + turnId + "-" + status,
                "knowledge-agent", "KNOWLEDGE_AGENT", null, status, "safe summary",
                "provider-call", "gpt-5.6-sol", List.of("tool.summary:1"), List.of("rag.case:1"),
                10L, now, now, now, now
        );
    }

    /**
     * 构造不同 applicationId 的事实，验证 Store 错误返回跨应用数据时 Service 仍会在对象层拒绝。
     *
     * <p>这里不复用默认 helper 的固定应用值，避免测试因为所有样本都碰巧属于同一应用而遗漏第三层边界。</p>
     */
    private SpecialistTurnFact factForApplication(String userId,
                                                  Long tenantId,
                                                  Long applicationId,
                                                  Long projectId,
                                                  String turnId) {
        Instant now = Instant.parse("2026-08-05T00:00:00Z");
        return new SpecialistTurnFact(
                userId, tenantId, applicationId, projectId, "session-a", "run-a", turnId,
                "idem-" + turnId, "knowledge-agent", "KNOWLEDGE_AGENT", null, "SUCCEEDED", "safe summary",
                "provider-call", "gpt-5.6-sol", List.of("tool.summary:1"), List.of("rag.case:1"),
                10L, now, now, now, now
        );
    }

    /**
     * 构造一条后确认 Specialist 成功事实，字段固定在完成判定测试使用的同一作用域。
     *
     * @param turnId Specialist turn 稳定 ID，也是子委托派生输入
     * @param agentId 实际执行该 turn 的专业 Agent 身份
     * @param role 专业角色码，也是子委托派生输入
     * @param delegationId 待验证的 Specialist 子委托
     * @return 带成功终态和一条可回查证据引用的低敏事实
     */
    private SpecialistTurnFact postConfirmFact(String turnId,
                                               String agentId,
                                               String role,
                                               String delegationId) {
        Instant now = Instant.parse("2026-08-05T00:00:00Z");
        return new SpecialistTurnFact(
                "user-a",
                10L,
                10010L,
                20L,
                "session-a",
                "run-a",
                turnId,
                "idem-" + turnId,
                agentId,
                role,
                delegationId,
                "COMPLETED",
                "safe summary",
                null,
                null,
                List.of("tool.summary:" + turnId),
                List.of("specialist.evidence:" + turnId),
                10L,
                now,
                now,
                now,
                now
        );
    }
}
