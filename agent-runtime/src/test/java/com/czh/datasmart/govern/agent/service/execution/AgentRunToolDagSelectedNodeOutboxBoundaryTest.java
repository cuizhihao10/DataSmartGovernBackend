/**
 * @Author : Cui
 * @Date: 2026/07/02 01:30
 * @Description DataSmart Govern Backend - AgentRunToolDagSelectedNodeOutboxBoundaryTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.execution;

import com.czh.datasmart.govern.agent.config.AgentRuntimePersistenceProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolDagExecutionDryRunResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolDagSelectedNodeOutboxEnqueueRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolDagSelectedNodeOutboxEnqueueResponse;
import com.czh.datasmart.govern.agent.persistence.AgentRuntimeJdbcConnectionManager;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DAG selected-node outbox 的输入与事务边界测试。
 *
 * <p>该类从综合行为测试中拆出三类边界：过期 dry-run 指纹、空节点选择和 MySQL 原子事务。
 * 它复用同包测试 fixture，但不复制生产构建逻辑，确保拆分只是测试职责整理，不改变被测服务。</p>
 */
class AgentRunToolDagSelectedNodeOutboxBoundaryTest {

    private static final String SESSION_ID = "session-selected-outbox-001";
    private static final String RUN_ID = "run-selected-outbox-001";

    @Test
    void staleDryRunFingerprintShouldFailClosed() {
        AgentRunToolDagSelectedNodeOutboxServiceTest source = new AgentRunToolDagSelectedNodeOutboxServiceTest();
        var fixture = source.newFixture();
        fixture.saveAudits(source.asyncAudit("audit-async", "data-sync-execute", Map.of()));

        assertThrows(PlatformBusinessException.class, () ->
                fixture.confirm(List.of("data-sync-execute"), "dag-selection:stale"));
        assertTrue(fixture.store().list(RUN_ID, null, 10).isEmpty());
        assertTrue(fixture.confirmationStore().listByRun(RUN_ID, 10).isEmpty());
    }

    @Test
    void confirmationMustExplicitlySelectNodes() {
        var fixture = new AgentRunToolDagSelectedNodeOutboxServiceTest().newFixture();

        assertThrows(PlatformBusinessException.class, () ->
                fixture.service().enqueueSelectedAsyncNodes(
                        SESSION_ID,
                        RUN_ID,
                        new AgentRunToolDagSelectedNodeOutboxEnqueueRequest(
                                List.of(), List.of(), null, "dag-selection:any", Map.of(), true, null
                        ),
                        "trace-selected"
                ));
    }

    @Test
    void mysqlOutboxAndConfirmationShouldShareJdbcTransactionBoundary() {
        RecordingDataSource dataSource = new RecordingDataSource();
        AgentRunToolDagSelectedNodeOutboxServiceTest source = new AgentRunToolDagSelectedNodeOutboxServiceTest();
        var fixture = source.newFixture(
                authorizationProperties -> { },
                request -> null,
                outboxProperties -> outboxProperties.setStore("mysql"),
                confirmationProperties -> confirmationProperties.setStore("mysql"),
                Optional.of(new AgentRuntimeJdbcConnectionManager(
                        dataSource.proxy(),
                        new AgentRuntimePersistenceProperties()
                ))
        );
        fixture.saveAudits(source.asyncAudit("audit-async", "data-sync-execute", Map.of()));
        AgentRunToolDagExecutionDryRunResponse dryRun = fixture.dryRun(List.of("data-sync-execute"));

        AgentRunToolDagSelectedNodeOutboxEnqueueResponse response = fixture.confirm(
                List.of("data-sync-execute"),
                dryRun.selectionFingerprint()
        );

        assertEquals(1, response.outbox().enqueuedCount());
        assertEquals(1, dataSource.connectionRequests);
        assertEquals(1, dataSource.commitCount);
        assertEquals(0, dataSource.rollbackCount);
    }
}
