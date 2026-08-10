/**
 * @Author : Cui
 * @Date: 2026/08/09 00:00
 * @Description DataSmart Govern Backend - AgentToolActionApprovalFactPostgreSqlIntegrationTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.integration;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.permission.service.support.AgentToolActionApprovalFactRecord;
import com.czh.datasmart.govern.permission.service.support.JdbcAgentToolActionApprovalFactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PostgreSQL-only regression coverage for the approval fact write invariant.
 * It is opt-in because the normal module test suite must not mutate a shared
 * development database.
 */
@SpringBootTest(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "datasmart.permission.policy-events.enabled=false",
        "datasmart.permission.policy-events.dispatcher-enabled=false"
})
@EnabledIfEnvironmentVariable(named = "DATASMART_POSTGRES_INTEGRATION_ENABLED", matches = "(?i)true")
class AgentToolActionApprovalFactPostgreSqlIntegrationTest {

    private final JdbcTemplate jdbcTemplate;
    private final JdbcAgentToolActionApprovalFactStore store;

    @Autowired
    AgentToolActionApprovalFactPostgreSqlIntegrationTest(
            JdbcTemplate jdbcTemplate,
            JdbcAgentToolActionApprovalFactStore store) {
        this.jdbcTemplate = jdbcTemplate;
        this.store = store;
    }

    @Test
    void terminalAndConflictingScopeWritesMustRemainFailClosed() throws Exception {
        String terminalId = uniqueId("terminal");
        String concurrentId = uniqueId("concurrent");
        try {
            AgentToolActionApprovalFactRecord approved = record(
                    terminalId, "user-a", "agent-a", "delegation-a", "policy-v1", "APPROVED");
            store.save(approved);

            AgentToolActionApprovalFactRecord delayedPending = record(
                    terminalId, "user-a", "agent-a", "delegation-a", "policy-v1", "PENDING");
            assertThat(store.save(delayedPending).status()).isEqualTo("APPROVED");
            assertThat(status(terminalId)).isEqualTo("APPROVED");

            AgentToolActionApprovalFactRecord conflicting = record(
                    terminalId, "user-b", "agent-b", "delegation-b", "policy-v2", "APPROVED");
            assertThatThrownBy(() -> store.save(conflicting))
                    .isInstanceOf(PlatformBusinessException.class);
            Map<String, Object> terminalRow = row(terminalId);
            assertThat(terminalRow.get("user_id")).isEqualTo("user-a");
            assertThat(terminalRow.get("agent_id")).isEqualTo("agent-a");
            assertThat(terminalRow.get("policy_version")).isEqualTo("policy-v1");

            runConcurrentConflictingWrites(concurrentId);
        } finally {
            jdbcTemplate.update(
                    "DELETE FROM agent_tool_action_approval_fact WHERE approval_fact_id IN (?, ?)",
                    terminalId, concurrentId);
        }
    }

    private void runConcurrentConflictingWrites(String approvalFactId) throws Exception {
        AgentToolActionApprovalFactRecord first = record(
                approvalFactId, "user-c", "agent-c", "delegation-c", "policy-v3", "PENDING");
        AgentToolActionApprovalFactRecord second = record(
                approvalFactId, "user-d", "agent-d", "delegation-d", "policy-v4", "PENDING");
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<AgentToolActionApprovalFactRecord>> futures = List.of(
                    executor.submit(() -> {
                        start.await();
                        return store.save(first);
                    }),
                    executor.submit(() -> {
                        start.await();
                        return store.save(second);
                    })
            );
            int successes = 0;
            List<Throwable> failures = new ArrayList<>();
            for (Future<AgentToolActionApprovalFactRecord> future : futures) {
                try {
                    future.get(5, TimeUnit.SECONDS);
                    successes++;
                } catch (ExecutionException exception) {
                    failures.add(exception.getCause());
                }
            }
            assertThat(successes).isEqualTo(1);
            assertThat(failures).singleElement().isInstanceOf(PlatformBusinessException.class);

            Map<String, Object> persisted = row(approvalFactId);
            String userId = (String) persisted.get("user_id");
            if ("user-c".equals(userId)) {
                assertThat(persisted.get("agent_id")).isEqualTo("agent-c");
                assertThat(persisted.get("delegation_id")).isEqualTo("delegation-c");
                assertThat(persisted.get("policy_version")).isEqualTo("policy-v3");
            } else {
                assertThat(userId).isEqualTo("user-d");
                assertThat(persisted.get("agent_id")).isEqualTo("agent-d");
                assertThat(persisted.get("delegation_id")).isEqualTo("delegation-d");
                assertThat(persisted.get("policy_version")).isEqualTo("policy-v4");
            }
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private Map<String, Object> row(String approvalFactId) {
        return jdbcTemplate.queryForMap("""
                SELECT user_id, agent_id, delegation_id, policy_version, status
                FROM agent_tool_action_approval_fact
                WHERE approval_fact_id = ?
                """, approvalFactId);
    }

    private String status(String approvalFactId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM agent_tool_action_approval_fact WHERE approval_fact_id = ?",
                String.class,
                approvalFactId);
    }

    private AgentToolActionApprovalFactRecord record(String approvalFactId,
                                                     String userId,
                                                     String agentId,
                                                     String delegationId,
                                                     String policyVersion,
                                                     String status) {
        return new AgentToolActionApprovalFactRecord(
                approvalFactId,
                10L,
                10010L,
                20L,
                userId,
                userId,
                agentId,
                "session-pg-regression",
                "run-pg-regression",
                delegationId,
                "command-pg-regression",
                "tool.read",
                policyVersion,
                status,
                LocalDateTime.now().plusMinutes(30),
                "reviewer-1",
                List.of("REGRESSION"),
                List.of("TEST"),
                LocalDateTime.now()
        );
    }

    private String uniqueId(String prefix) {
        return "approval:pg-" + prefix + "-" + UUID.randomUUID().toString().replace("-", "");
    }
}
