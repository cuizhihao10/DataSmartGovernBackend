/**
 * @Author : Cui
 * @Date: 2026/06/28 22:45
 * @Description DataSmart Govern Backend - AgentToolActionSubmissionFactRecoveryServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionSubmissionManualResolutionRequest;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent 工具真实提交事实恢复服务测试。
 *
 * <p>这些测试保护的是生产闭环里很容易被忽视的部分：异常不确定状态的人工收口。
 * 如果 UNKNOWN 不能查询，运维就只能翻日志；如果 UNKNOWN 可以被任意角色修改，就会破坏副作用审计；
 * 如果 dry-run 默认直接落库，误点一次运营按钮就可能把事实推进到错误终态。</p>
 */
class AgentToolActionSubmissionFactRecoveryServiceTest {

    @Test
    void queryShouldReturnLowSensitiveFactViewWithoutRawReferences() {
        InMemoryAgentToolActionSubmissionFactStore store = storeWith(unknownFact());
        AgentToolActionSubmissionFactRecoveryService service = service(store);

        var response = service.query(
                "command-remediation-001",
                null,
                "20",
                null,
                "run-001",
                "session-001",
                operatorContext()
        );

        assertTrue(response.factPresent());
        assertEquals("UNKNOWN", response.fact().status());
        assertEquals("idem-remediation-001", response.fact().idempotencyKey());
        assertNotNull(response.fact().payloadReferenceFingerprint());
        assertNotEquals("agent-payload:run-001/quality-remediation-task-draft:audit-001",
                response.fact().payloadReferenceFingerprint());
        assertTrue(response.issueCodes().contains("SUBMISSION_STATUS_UNKNOWN"));
        assertFalse(response.toString().contains("topAnomalyTypes"));
        assertFalse(response.toString().contains("select "));
    }

    @Test
    void manualResolutionShouldDefaultToDryRunAndNotUpdateStore() {
        InMemoryAgentToolActionSubmissionFactStore store = storeWith(unknownFact());
        AgentToolActionSubmissionFactRecoveryService service = service(store);

        var response = service.resolveUnknown(
                "command-remediation-001",
                new AgentToolActionSubmissionManualResolutionRequest(
                        "SUBMITTED",
                        7001L,
                        "PENDING",
                        null,
                        "DOWNSTREAM_TASK_FOUND",
                        "人工对账确认任务已创建",
                        null,
                        null,
                        "20",
                        null,
                        "run-001",
                        "session-001"
                ),
                operatorContext()
        );

        assertTrue(response.accepted());
        assertTrue(response.dryRun());
        assertFalse(response.updated());
        assertEquals("SUBMITTED", response.after().status());
        assertEquals(AgentToolActionSubmissionStatus.UNKNOWN,
                store.findByCommandId("command-remediation-001").orElseThrow().status());
    }

    @Test
    void manualResolutionShouldPersistSubmittedWhenDryRunFalse() {
        InMemoryAgentToolActionSubmissionFactStore store = storeWith(unknownFact());
        AgentToolActionSubmissionFactRecoveryService service = service(store);

        var response = service.resolveUnknown(
                "command-remediation-001",
                new AgentToolActionSubmissionManualResolutionRequest(
                        "SUBMITTED",
                        7001L,
                        "PENDING",
                        null,
                        "DOWNSTREAM_TASK_FOUND",
                        null,
                        false,
                        null,
                        "20",
                        null,
                        "run-001",
                        "session-001"
                ),
                operatorContext()
        );

        assertFalse(response.dryRun());
        assertTrue(response.updated());
        assertEquals("SUBMITTED", response.after().status());
        assertEquals(7001L, response.after().downstreamTaskId());
        assertEquals(AgentToolActionSubmissionStatus.SUBMITTED,
                store.findByCommandId("command-remediation-001").orElseThrow().status());
    }

    @Test
    void manualResolutionShouldRejectOrdinaryUser() {
        InMemoryAgentToolActionSubmissionFactStore store = storeWith(unknownFact());
        AgentToolActionSubmissionFactRecoveryService service = service(store);

        assertThrows(PlatformBusinessException.class, () -> service.resolveUnknown(
                "command-remediation-001",
                new AgentToolActionSubmissionManualResolutionRequest(
                        "REJECTED",
                        null,
                        null,
                        null,
                        "DOWNSTREAM_TASK_NOT_FOUND",
                        null,
                        false,
                        null,
                        "20",
                        null,
                        "run-001",
                        "session-001"
                ),
                new AgentRuntimeEventQueryAccessContext(10L, 1001L, "ORDINARY_USER", "trace", "SELF", List.of())
        ));
    }

    @Test
    void manualResolutionShouldRejectSensitiveOperatorNote() {
        InMemoryAgentToolActionSubmissionFactStore store = storeWith(unknownFact());
        AgentToolActionSubmissionFactRecoveryService service = service(store);

        assertThrows(PlatformBusinessException.class, () -> service.resolveUnknown(
                "command-remediation-001",
                new AgentToolActionSubmissionManualResolutionRequest(
                        "REJECTED",
                        null,
                        null,
                        null,
                        "DOWNSTREAM_TASK_NOT_FOUND",
                        "select * from quality_anomaly_detail",
                        false,
                        null,
                        "20",
                        null,
                        "run-001",
                        "session-001"
                ),
                operatorContext()
        ));
    }

    private AgentToolActionSubmissionFactRecoveryService service(InMemoryAgentToolActionSubmissionFactStore store) {
        return new AgentToolActionSubmissionFactRecoveryService(
                store,
                new AgentRuntimeEventProjectionAccessSupport()
        );
    }

    private InMemoryAgentToolActionSubmissionFactStore storeWith(AgentToolActionSubmissionFactRecord record) {
        InMemoryAgentToolActionSubmissionFactStore store = new InMemoryAgentToolActionSubmissionFactStore();
        store.save(record);
        return store;
    }

    private AgentRuntimeEventQueryAccessContext operatorContext() {
        return new AgentRuntimeEventQueryAccessContext(10L, 9001L, "OPERATOR", "trace", "TENANT", List.of());
    }

    private AgentToolActionSubmissionFactRecord unknownFact() {
        Instant now = Instant.now();
        return new AgentToolActionSubmissionFactRecord(
                AgentToolActionSubmissionFactRecord.identityKey("command-remediation-001"),
                "command-remediation-001",
                "idem-remediation-001",
                "session-001",
                "run-001",
                "audit-remediation-001",
                "quality.remediation.task.draft",
                "10",
                "20",
                "1001",
                "agent-payload:run-001/quality-remediation-task-draft:audit-001",
                "tool-action-confirmation:remediation-001",
                "tool-readiness-policy.v1",
                "data-quality",
                "/quality-rules/remediation-tasks",
                AgentToolActionSubmissionStatus.UNKNOWN,
                true,
                false,
                "EXECUTION_STATUS_UNKNOWN",
                null,
                null,
                "QUALITY_REMEDIATION_SUBMIT_UNKNOWN",
                List.of("QUALITY_REMEDIATION_SUBMIT_UNKNOWN"),
                List.of("先按 idempotencyKey 对账 data-quality/task-management。"),
                "调用 data-quality 后响应不可确认，等待人工对账。",
                now.minusSeconds(30),
                now
        );
    }
}
