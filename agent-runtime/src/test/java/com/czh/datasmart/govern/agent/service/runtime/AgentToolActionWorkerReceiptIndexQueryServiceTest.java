/**
 * @Author : Cui
 * @Date: 2026/06/18 00:00
 * @Description DataSmart Govern Backend - AgentToolActionWorkerReceiptIndexQueryServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.config.AgentToolActionResumeFactBundleProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionWorkerReceiptIndexQueryResponse;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * worker receipt 低敏查询面测试。
 *
 * <p>这组测试保护 5.82 的产品语义：管理台可以按 commandId 查询 receipt host fact；
 * 查询必须继续遵守租户/项目/actor 数据范围；响应只能暴露低敏机器事实和短指纹，不能把 receipt 原文、
 * eventIdentityKey、prompt、SQL、payload、工具参数或密钥重新暴露出去。</p>
 */
class AgentToolActionWorkerReceiptIndexQueryServiceTest {

    @Test
    void queryShouldReturnLowSensitiveReceiptsWithinProjectScope() throws JsonProcessingException {
        InMemoryAgentToolActionWorkerReceiptIndexStore store = new InMemoryAgentToolActionWorkerReceiptIndexStore(100);
        store.upsert(receipt("receipt-secret-identity-key", "20", "1001", true, "DRY_RUN_PASSED", 11L));
        AgentToolActionWorkerReceiptIndexQueryService service = service(store, "mysql");

        AgentToolActionWorkerReceiptIndexQueryResponse response = service.queryReceipts(
                "taoc-receipt-query-001",
                "datasource.metadata.read",
                null,
                null,
                null,
                "run-receipt-query",
                "session-receipt-query",
                10,
                projectScope(List.of(20L))
        );

        assertEquals(10, response.appliedLimit());
        assertEquals(1, response.totalMatched());
        assertEquals("MYSQL", response.storeMode());
        assertEquals("COMMAND_ID_REQUIRED_LOW_SENSITIVE_WORKER_RECEIPT_INDEX", response.queryMode());
        assertEquals(List.of("20"), response.authorizedProjectIds());
        assertTrue(response.evidenceCodes().contains("WORKER_RECEIPT_INDEX_RECORDS_FOUND"));
        assertFalse(response.missingCapabilities().contains("MYSQL_DURABLE_WORKER_RECEIPT_INDEX"));
        var receipt = response.receipts().getFirst();
        assertEquals("taoc-receipt-query-001", receipt.commandId());
        assertEquals("20", receipt.projectId());
        assertEquals("DRY_RUN_PASSED", receipt.outcome());
        assertTrue(receipt.eventIdentityKeyPresent());
        assertNotEquals("receipt-secret-identity-key", receipt.eventIdentityKeyFingerprint());
        assertEquals(16, receipt.eventIdentityKeyFingerprint().length());
        String json = objectMapper().writeValueAsString(response);
        assertFalse(json.contains("receipt-secret-identity-key"));
        assertFalse(json.contains("select * from sensitive_table"));
        assertFalse(json.contains("raw prompt should not leak"));
        assertFalse(json.contains("payloadBody"));
        assertFalse(json.contains("secret-token"));
        assertFalse(json.contains("arguments"));
    }

    @Test
    void queryShouldRejectExplicitProjectOutsideAuthorizedScope() {
        InMemoryAgentToolActionWorkerReceiptIndexStore store = new InMemoryAgentToolActionWorkerReceiptIndexStore(100);
        store.upsert(receipt("receipt-project-20", "20", "1001", true, "DRY_RUN_PASSED", 1L));
        AgentToolActionWorkerReceiptIndexQueryService service = service(store, "memory");

        PlatformBusinessException exception = assertThrows(PlatformBusinessException.class, () -> service.queryReceipts(
                "taoc-receipt-query-001",
                null,
                null,
                "99",
                null,
                null,
                null,
                10,
                projectScope(List.of(20L))
        ));

        assertEquals(PlatformErrorCode.TENANT_SCOPE_DENIED, exception.getErrorCode());
    }

    @Test
    void queryShouldReturnEmptyWhenProjectScopeHasNoAuthorizedProjects() {
        InMemoryAgentToolActionWorkerReceiptIndexStore store = new InMemoryAgentToolActionWorkerReceiptIndexStore(100);
        store.upsert(receipt("receipt-hidden", "20", "1001", true, "DRY_RUN_PASSED", 1L));
        AgentToolActionWorkerReceiptIndexQueryService service = service(store, "memory");

        AgentToolActionWorkerReceiptIndexQueryResponse response = service.queryReceipts(
                "taoc-receipt-query-001",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                projectScope(List.of())
        );

        assertEquals(50, response.appliedLimit());
        assertEquals(0, response.totalMatched());
        assertTrue(response.authorizedProjectIds().isEmpty());
        assertTrue(response.evidenceCodes().contains("WORKER_RECEIPT_INDEX_PROJECT_SCOPE_EMPTY"));
    }

    @Test
    void queryShouldRequireCommandId() {
        AgentToolActionWorkerReceiptIndexQueryService service =
                service(new InMemoryAgentToolActionWorkerReceiptIndexStore(100), "memory");

        PlatformBusinessException exception = assertThrows(PlatformBusinessException.class, () -> service.queryReceipts(
                " ",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                tenantScope()
        ));

        assertEquals(PlatformErrorCode.BAD_REQUEST, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("commandId"));
    }

    @Test
    void queryShouldRespectSelfActorScope() {
        InMemoryAgentToolActionWorkerReceiptIndexStore store = new InMemoryAgentToolActionWorkerReceiptIndexStore(100);
        store.upsert(receipt("receipt-other-actor", "20", "1002", true, "DRY_RUN_PASSED", 1L));
        AgentToolActionWorkerReceiptIndexQueryService service = service(store, "memory");

        AgentToolActionWorkerReceiptIndexQueryResponse response = service.queryReceipts(
                "taoc-receipt-query-001",
                null,
                null,
                null,
                null,
                null,
                null,
                10,
                selfScope()
        );

        assertEquals(0, response.totalMatched());
        assertEquals("1001", response.scopedActorId());
        assertTrue(response.evidenceCodes().contains("WORKER_RECEIPT_INDEX_RECORDS_NOT_FOUND"));
    }

    private AgentToolActionWorkerReceiptIndexQueryService service(InMemoryAgentToolActionWorkerReceiptIndexStore store,
                                                                  String storeMode) {
        AgentToolActionResumeFactBundleProperties properties = new AgentToolActionResumeFactBundleProperties();
        properties.setWorkerReceiptIndexStore(storeMode);
        return new AgentToolActionWorkerReceiptIndexQueryService(
                new AgentToolActionWorkerReceiptIndexService(store),
                new AgentRuntimeEventProjectionAccessSupport(),
                properties
        );
    }

    private AgentToolActionWorkerReceiptIndexRecord receipt(String identityKey,
                                                            String projectId,
                                                            String actorId,
                                                            boolean preCheckPassed,
                                                            String outcome,
                                                            long replaySequence) {
        return new AgentToolActionWorkerReceiptIndexRecord(
                identityKey,
                "taoc-receipt-query-001",
                "10",
                projectId,
                actorId,
                "run-receipt-query",
                "session-receipt-query",
                "datasource.metadata.read",
                preCheckPassed ? "RUNNING" : "FAILED",
                outcome,
                preCheckPassed,
                false,
                preCheckPassed ? null : "AGENT_TOOL_ACTION_CONTROLLED_PRECHECK_REJECTED",
                replaySequence,
                Instant.parse("2026-06-18T00:00:00Z").plusSeconds(replaySequence),
                Instant.parse("2026-06-18T01:00:00Z")
        );
    }

    private AgentRuntimeEventQueryAccessContext projectScope(List<Long> projectIds) {
        return new AgentRuntimeEventQueryAccessContext(
                10L,
                1001L,
                "PROJECT_OWNER",
                "trace-worker-receipt-query",
                "PROJECT",
                projectIds
        );
    }

    private AgentRuntimeEventQueryAccessContext selfScope() {
        return new AgentRuntimeEventQueryAccessContext(
                10L,
                1001L,
                "ORDINARY_USER",
                "trace-worker-receipt-self",
                "SELF",
                List.of()
        );
    }

    private AgentRuntimeEventQueryAccessContext tenantScope() {
        return new AgentRuntimeEventQueryAccessContext(
                10L,
                1001L,
                "TENANT_ADMINISTRATOR",
                "trace-worker-receipt-tenant",
                "TENANT",
                List.of()
        );
    }

    private ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
