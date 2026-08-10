/**
 * @Author : Cui
 * @Date: 2026/08/09 00:00
 * @Description DataSmart Govern Backend - InMemoryAgentToolActionApprovalFactStoreTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service.support;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryAgentToolActionApprovalFactStoreTest {

    @Test
    void concurrentDualSubjectAndPolicyVersionUpdatesMustFailClosed() throws Exception {
        InMemoryAgentToolActionApprovalFactStore store = new InMemoryAgentToolActionApprovalFactStore();
        AgentToolActionApprovalFactRecord first = record("user-a", "agent-a", "delegation-a", "policy-v1");
        AgentToolActionApprovalFactRecord second = record("user-b", "agent-b", "delegation-b", "policy-v2");
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

            AgentToolActionApprovalFactRecord persisted = store.findById(first.approvalFactId()).orElseThrow();
            assertThat(persisted.userId()).isIn(first.userId(), second.userId());
            if (first.userId().equals(persisted.userId())) {
                assertThat(persisted.agentId()).isEqualTo(first.agentId());
                assertThat(persisted.delegationId()).isEqualTo(first.delegationId());
                assertThat(persisted.policyVersion()).isEqualTo(first.policyVersion());
            } else {
                assertThat(persisted.agentId()).isEqualTo(second.agentId());
                assertThat(persisted.delegationId()).isEqualTo(second.delegationId());
                assertThat(persisted.policyVersion()).isEqualTo(second.policyVersion());
            }
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private AgentToolActionApprovalFactRecord record(String userId,
                                                     String agentId,
                                                     String delegationId,
                                                     String policyVersion) {
        return new AgentToolActionApprovalFactRecord(
                "approval:concurrent-001",
                10L,
                10010L,
                20L,
                userId,
                userId,
                agentId,
                "session-concurrent",
                "run-concurrent",
                delegationId,
                "command-concurrent",
                "tool.read",
                policyVersion,
                "PENDING",
                LocalDateTime.now().plusMinutes(30),
                null,
                List.of(),
                List.of(),
                LocalDateTime.now()
        );
    }
}
