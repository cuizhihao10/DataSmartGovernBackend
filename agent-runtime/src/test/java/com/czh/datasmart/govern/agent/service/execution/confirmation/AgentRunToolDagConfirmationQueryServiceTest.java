/**
 * @Author : Cui
 * @Date: 2026/06/01 22:29
 * @Description DataSmart Govern Backend - AgentRunToolDagConfirmationQueryServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.execution.confirmation;

import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventQueryAccessContext;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DAG selected-node 确认记录查询服务测试。
 *
 * <p>这些用例保护的是“审计可读但不能越权”的产品语义：
 * confirmation 已经是只读证据，不会触发工具执行；但它仍然必须按 actor、project、tenant 做范围收口，
 * 否则审计查询接口会成为绕过 Agent 执行权限的侧门。</p>
 */
class AgentRunToolDagConfirmationQueryServiceTest {

    @Test
    void projectOwnerShouldOnlySeeAuthorizedProjectConfirmations() {
        AgentRunToolDagConfirmationQueryService service = serviceWithTwoProjects();
        AgentRuntimeEventQueryAccessContext context = new AgentRuntimeEventQueryAccessContext(
                10L,
                2001L,
                "PROJECT_OWNER",
                "trace-project-confirmation",
                "PROJECT",
                List.of(20L)
        );

        var response = service.listByRun("session-a", "run-a", 20, context);

        assertThat(response.totalMatched()).isEqualTo(1);
        assertThat(response.confirmations().getFirst().projectId()).isEqualTo(20L);
        assertThat(response.confirmations().getFirst().confirmationId()).isEqualTo("confirmation-project-20");
    }

    @Test
    void ordinaryUserShouldOnlySeeOwnConfirmations() {
        AgentRunToolDagConfirmationQueryService service = serviceWithTwoActors();
        AgentRuntimeEventQueryAccessContext context = new AgentRuntimeEventQueryAccessContext(
                10L,
                1001L,
                "ORDINARY_USER",
                "trace-self-confirmation",
                "SELF",
                List.of()
        );

        var response = service.listByRun("session-a", "run-a", null, context);

        assertThat(response.limit()).isEqualTo(50);
        assertThat(response.totalMatched()).isEqualTo(1);
        assertThat(response.confirmations().getFirst().actorId()).isEqualTo("1001");
    }

    @Test
    void detailQueryShouldRejectSessionOrRunMismatch() {
        AgentRunToolDagConfirmationQueryService service = serviceWithTwoProjects();
        AgentRuntimeEventQueryAccessContext context = new AgentRuntimeEventQueryAccessContext(
                10L,
                9001L,
                "AUDITOR",
                "trace-mismatch",
                "TENANT",
                List.of()
        );

        assertThatThrownBy(() -> service.getByConfirmationId(
                "other-session",
                "run-a",
                "confirmation-project-20",
                context
        )).isInstanceOf(PlatformBusinessException.class)
                .hasMessageContaining("不匹配");
    }

    @Test
    void detailQueryShouldRejectOutOfScopeConfirmation() {
        AgentRunToolDagConfirmationQueryService service = serviceWithTwoProjects();
        AgentRuntimeEventQueryAccessContext context = new AgentRuntimeEventQueryAccessContext(
                10L,
                2001L,
                "PROJECT_OWNER",
                "trace-out-of-scope",
                "PROJECT",
                List.of(20L)
        );

        assertThatThrownBy(() -> service.getByConfirmationId(
                "session-a",
                "run-a",
                "confirmation-project-30",
                context
        )).isInstanceOf(PlatformBusinessException.class)
                .hasMessageContaining("不能查看");
    }

    private AgentRunToolDagConfirmationQueryService serviceWithTwoProjects() {
        InMemoryAgentRunToolDagConfirmationStore store = new InMemoryAgentRunToolDagConfirmationStore(20, 100);
        store.saveIfAbsent(record("confirmation-project-20", "session-a", "run-a", 20L, "1001"));
        store.saveIfAbsent(record("confirmation-project-30", "session-a", "run-a", 30L, "1002"));
        return new AgentRunToolDagConfirmationQueryService(store, new AgentRunToolDagConfirmationAccessSupport());
    }

    private AgentRunToolDagConfirmationQueryService serviceWithTwoActors() {
        InMemoryAgentRunToolDagConfirmationStore store = new InMemoryAgentRunToolDagConfirmationStore(20, 100);
        store.saveIfAbsent(record("confirmation-actor-1001", "session-a", "run-a", 20L, "1001"));
        store.saveIfAbsent(record("confirmation-actor-1002", "session-a", "run-a", 20L, "1002"));
        return new AgentRunToolDagConfirmationQueryService(store, new AgentRunToolDagConfirmationAccessSupport());
    }

    private AgentRunToolDagConfirmationRecord record(String confirmationId,
                                                     String sessionId,
                                                     String runId,
                                                     Long projectId,
                                                     String actorId) {
        return new AgentRunToolDagConfirmationRecord(
                confirmationId,
                sessionId,
                runId,
                "fingerprint-" + confirmationId,
                List.of("node-a"),
                List.of("audit-a"),
                List.of("route-policy:1"),
                List.of("serviceAccount=datasmart-agent-runtime;representedActor=" + actorId),
                List.of("outbox-a"),
                List.of("command-a"),
                10L,
                projectId,
                300L,
                actorId,
                "trace-" + confirmationId,
                true,
                AgentRunToolDagConfirmationStatus.CONFIRMED,
                Instant.parse("2026-06-01T23:00:00Z"),
                Instant.parse("2026-06-01T22:00:00Z"),
                Instant.parse("2026-06-01T22:00:00Z")
        );
    }
}
