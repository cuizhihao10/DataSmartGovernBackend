/**
 * @Author : Cui
 * @Date: 2026/06/17 00:00
 * @Description DataSmart Govern Backend - AgentToolActionClarificationFactEvaluatorTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.config.AgentToolActionResumeFactBundleProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionResumeFactBundleQueryRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionResumeFactView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 澄清事实 evaluator 测试。
 *
 * <p>这组测试保护的是 Human-in-the-loop 恢复预检中最容易出问题的边界：
 * factId 不能自证有效、跨项目事实不能被探测、过期或撤销事实不能继续执行、最终 JSON 不应泄露用户补充原文。</p>
 */
class AgentToolActionClarificationFactEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-06-17T00:00:00Z");

    @Test
    void availableRecordShouldBecomeAvailableFactWithoutLeakingContent() throws JsonProcessingException {
        TestHarness harness = harness();
        harness.store().upsert(record("clarification-ready", "20",
                AgentToolActionClarificationFactRecord.STATUS_AVAILABLE, NOW.plusSeconds(3600)));

        AgentToolActionResumeFactView fact = harness.evaluator().evaluate(
                request("clarification-ready", "20", "tool-readiness-policy.v1"),
                scopedQuery("20"),
                accessContext(),
                NOW
        );

        assertEquals("CLARIFICATION_FACT", fact.factType());
        assertEquals("AVAILABLE", fact.status());
        assertTrue(fact.available());
        assertTrue(fact.evidenceCodes().contains("CLARIFICATION_FACT_CONTENT_NOT_EXPOSED"));

        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(fact);
        assertFalse(json.contains("user clarification raw answer should not leak"));
        assertFalse(json.contains("select * from sensitive_table"));
        assertFalse(json.contains("raw prompt should not leak"));
    }

    @Test
    void recordOutsideAuthorizedProjectShouldBeHiddenAsMissing() {
        TestHarness harness = harness();
        harness.store().upsert(record("clarification-outside-project", "99",
                AgentToolActionClarificationFactRecord.STATUS_AVAILABLE, NOW.plusSeconds(3600)));

        AgentToolActionResumeFactView fact = harness.evaluator().evaluate(
                request("clarification-outside-project", "20", "tool-readiness-policy.v1"),
                scopedQuery("20"),
                accessContext(),
                NOW
        );

        assertEquals("MISSING", fact.status());
        assertFalse(fact.available());
        assertTrue(fact.issueCodes().contains("CLARIFICATION_FACT_NOT_FOUND_OR_NOT_VISIBLE"));
    }

    @Test
    void expiredRecordShouldBeRejected() {
        TestHarness harness = harness();
        harness.store().upsert(record("clarification-expired", "20",
                AgentToolActionClarificationFactRecord.STATUS_AVAILABLE, NOW.minusSeconds(1)));

        AgentToolActionResumeFactView fact = harness.evaluator().evaluate(
                request("clarification-expired", "20", "tool-readiness-policy.v1"),
                scopedQuery("20"),
                accessContext(),
                NOW
        );

        assertEquals("REJECTED", fact.status());
        assertTrue(fact.rejected());
        assertTrue(fact.issueCodes().contains("CLARIFICATION_FACT_EXPIRED"));
    }

    @Test
    void revokedRecordShouldBeRejected() {
        TestHarness harness = harness();
        harness.store().upsert(record("clarification-revoked", "20",
                AgentToolActionClarificationFactRecord.STATUS_REVOKED, NOW.plusSeconds(3600)));

        AgentToolActionResumeFactView fact = harness.evaluator().evaluate(
                request("clarification-revoked", "20", "tool-readiness-policy.v1"),
                scopedQuery("20"),
                accessContext(),
                NOW
        );

        assertEquals("REJECTED", fact.status());
        assertTrue(fact.rejected());
        assertTrue(fact.issueCodes().contains("CLARIFICATION_FACT_REVOKED"));
    }

    @Test
    void policyVersionMismatchShouldBeRejected() {
        TestHarness harness = harness();
        harness.store().upsert(record("clarification-policy-old", "20",
                AgentToolActionClarificationFactRecord.STATUS_AVAILABLE, NOW.plusSeconds(3600)));

        AgentToolActionResumeFactView fact = harness.evaluator().evaluate(
                request("clarification-policy-old", "20", "tool-readiness-policy.v2"),
                scopedQuery("20"),
                accessContext(),
                NOW
        );

        assertEquals("REJECTED", fact.status());
        assertTrue(fact.issueCodes().contains("CLARIFICATION_FACT_POLICY_VERSION_MISMATCH"));
    }

    private TestHarness harness() {
        AgentToolActionClarificationFactStore store =
                new InMemoryAgentToolActionClarificationFactStore(new AgentToolActionResumeFactBundleProperties());
        return new TestHarness(store, new AgentToolActionClarificationFactEvaluator(store));
    }

    private AgentToolActionClarificationFactRecord record(String factId,
                                                          String projectId,
                                                          String status,
                                                          Instant expiresAt) {
        return new AgentToolActionClarificationFactRecord(
                factId,
                "session-resume",
                "run-resume",
                "taoc-resume-001",
                "datasource.metadata.read",
                "tool-readiness-policy.v1",
                "10",
                projectId,
                "1001",
                status,
                List.of("USER_CLARIFICATION_CAPTURED"),
                List.of("NO_RAW_CONTENT_USER_CLARIFICATION_RAW_ANSWER_SHOULD_NOT_LEAK"),
                expiresAt,
                NOW,
                NOW
        );
    }

    private AgentToolActionResumeFactBundleQueryRequest request(String factId,
                                                                String projectId,
                                                                String policyVersion) {
        return new AgentToolActionResumeFactBundleQueryRequest(
                "checkpoint-resume-001",
                "thread-resume-001",
                "session-resume",
                "run-resume",
                "taoc-resume-001",
                "async-command-outbox:taoc-resume-001",
                null,
                factId,
                "datasource.metadata.read",
                policyVersion,
                10L,
                Long.valueOf(projectId),
                "1001",
                List.of("CLARIFICATION_FACT"),
                true,
                true
        );
    }

    private AgentRuntimeEventProjectionQuery scopedQuery(String projectId) {
        return new AgentRuntimeEventProjectionQuery(
                "10",
                projectId,
                "1001",
                null,
                "run-resume",
                "session-resume",
                null,
                null,
                50,
                List.of("20")
        );
    }

    private AgentRuntimeEventQueryAccessContext accessContext() {
        return new AgentRuntimeEventQueryAccessContext(
                10L,
                1001L,
                "PROJECT_OWNER",
                "trace-resume",
                "PROJECT",
                List.of(20L)
        );
    }

    private record TestHarness(
            AgentToolActionClarificationFactStore store,
            AgentToolActionClarificationFactEvaluator evaluator
    ) {
    }
}
