/**
 * @Author : Cui
 * @Date: 2026/06/29 00:00
 * @Description DataSmart Govern Backend - AgentWorkspaceFileWorkerReceiptServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentCommandWorkerLeaseClaimRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionCommandWorkerReceiptRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentWorkspaceFileWorkerReceiptRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentWorkspaceFileWorkerReceiptResponse;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Workspace 文件 worker 回执适配服务测试。
 *
 * <p>本测试保护 workspace 文件工具从“payload 已物化”推进到“worker 回执可进入 timeline/index”的闭口边界。
 * 重点不是验证真实文件读写，而是验证 worker 写回前必须能绑定服务端 payload fact，并且响应、runtime event、
 * worker receipt index 都不泄露 relativePath、content、contentReference 原值或其他高敏正文。</p>
 */
class AgentWorkspaceFileWorkerReceiptServiceTest {

    private static final String SESSION_ID = "session-file-receipt";
    private static final String RUN_ID = "run-file-receipt";
    private static final String COMMAND_ID = "cmd-file-001";
    private static final String EXECUTOR_ID = "agent-file-worker";

    @Test
    void receiveShouldVerifyPayloadAndDelegateToCommandWorkerReceipt() throws JsonProcessingException {
        TestFixture fixture = fixture();
        String content = "controlled file update\n";
        String payloadReference = materializeWritePayload(fixture.payloadStore(), content);
        AgentCommandWorkerLeaseRecord lease = claimLease(fixture.leaseService());

        AgentWorkspaceFileWorkerReceiptResponse response = fixture.service().receive(
                SESSION_ID,
                RUN_ID,
                "trace-file-receipt",
                new AgentWorkspaceFileWorkerReceiptRequest(
                        payloadReference,
                        "WRITE",
                        successReceipt(lease)
                )
        );

        assertTrue(response.accepted());
        assertFalse(response.duplicate());
        assertEquals("EXECUTION_SUCCEEDED", response.outcome());
        assertEquals("workspace.file.write", response.toolCode());
        assertEquals("WRITE", response.operation());
        assertTrue(response.evidenceCodes().contains("WORKSPACE_FILE_PAYLOAD_SCOPE_VERIFIED"));
        assertFalse(response.toString().contains("docs/worker-note.md"));
        assertFalse(response.toString().contains(content));

        AgentRuntimeEventProjectionRecord record = fixture.projectionStore().listByRunId(RUN_ID).getFirst();
        assertEquals(AgentToolActionCommandWorkerReceiptService.EVENT_TYPE, record.eventType());
        assertEquals(COMMAND_ID, record.attributes().get("commandId"));
        assertEquals(true, record.attributes().get("sideEffectExecuted"));
        assertEquals("agent-artifact:run-file-receipt/receipt-001", record.attributes().get("artifactReference"));

        List<AgentToolActionWorkerReceiptIndexRecord> receipts =
                fixture.indexStore().queryByCommandId(new AgentToolActionWorkerReceiptIndexQuery(
                        COMMAND_ID,
                        null,
                        "10",
                        "20",
                        "30",
                        RUN_ID,
                        SESSION_ID,
                        List.of("20"),
                        10
                ));
        assertEquals(1, receipts.size());
        assertEquals("EXECUTION_SUCCEEDED", receipts.getFirst().outcome());

        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(record);
        assertFalse(json.contains("docs/worker-note.md"));
        assertFalse(json.contains(content));
        assertFalse(json.contains("contentReference"));
        assertFalse(json.contains(lease.fencingToken()));
    }

    @Test
    void receiveShouldRejectMissingPayloadReferenceBeforeTimelineWrite() {
        TestFixture fixture = fixture();
        AgentCommandWorkerLeaseRecord lease = claimLease(fixture.leaseService());

        PlatformBusinessException exception = assertThrows(PlatformBusinessException.class, () ->
                fixture.service().receive(
                        SESSION_ID,
                        RUN_ID,
                        "trace-missing-payload",
                        new AgentWorkspaceFileWorkerReceiptRequest(
                                "agent-payload:run-file-receipt/missing",
                                "WRITE",
                                successReceipt(lease)
                        )
                )
        );

        assertEquals(PlatformErrorCode.BAD_REQUEST, exception.getErrorCode());
        assertTrue(fixture.projectionStore().listByRunId(RUN_ID).isEmpty());
    }

    @Test
    void receiveShouldRejectOperationAndToolMismatch() {
        TestFixture fixture = fixture();
        String payloadReference = materializeWritePayload(fixture.payloadStore(), "safe content\n");
        AgentCommandWorkerLeaseRecord lease = claimLease(fixture.leaseService());

        PlatformBusinessException exception = assertThrows(PlatformBusinessException.class, () ->
                fixture.service().receive(
                        SESSION_ID,
                        RUN_ID,
                        "trace-tool-mismatch",
                        new AgentWorkspaceFileWorkerReceiptRequest(
                                payloadReference,
                                "WRITE",
                                receiptWithTool(lease, "workspace.file.read", 30L)
                        )
                )
        );

        assertEquals(PlatformErrorCode.BAD_REQUEST, exception.getErrorCode());
    }

    @Test
    void receiveShouldRejectPayloadScopeMismatch() {
        TestFixture fixture = fixture();
        String payloadReference = materializeWritePayload(fixture.payloadStore(), "safe content\n");
        AgentCommandWorkerLeaseRecord lease = claimLease(fixture.leaseService());

        PlatformBusinessException exception = assertThrows(PlatformBusinessException.class, () ->
                fixture.service().receive(
                        SESSION_ID,
                        RUN_ID,
                        "trace-scope-mismatch",
                        new AgentWorkspaceFileWorkerReceiptRequest(
                                payloadReference,
                                "WRITE",
                                receiptWithTool(lease, "workspace.file.write", 31L)
                        )
                )
        );

        assertEquals(PlatformErrorCode.FORBIDDEN, exception.getErrorCode());
    }

    private TestFixture fixture() {
        InMemoryAgentToolActionPayloadStore payloadStore = new InMemoryAgentToolActionPayloadStore();
        InMemoryAgentRuntimeEventProjectionStore projectionStore =
                new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        InMemoryAgentToolActionWorkerReceiptIndexStore indexStore =
                new InMemoryAgentToolActionWorkerReceiptIndexStore(100);
        AgentCommandWorkerLeaseService leaseService =
                new AgentCommandWorkerLeaseService(new InMemoryAgentCommandWorkerLeaseStore());
        AgentToolActionCommandWorkerReceiptService commandReceiptService =
                new AgentToolActionCommandWorkerReceiptService(
                        projectionStore,
                        new AgentToolActionWorkerReceiptIndexService(indexStore),
                        leaseService
                );
        return new TestFixture(
                payloadStore,
                projectionStore,
                indexStore,
                leaseService,
                new AgentWorkspaceFileWorkerReceiptService(payloadStore, commandReceiptService)
        );
    }

    private String materializeWritePayload(InMemoryAgentToolActionPayloadStore payloadStore, String content) {
        AgentWorkspaceFilePayloadMaterializationService materializationService =
                new AgentWorkspaceFilePayloadMaterializationService(
                        new AgentToolActionPayloadMaterializationService(payloadStore)
                );
        AgentWorkspaceFilePayloadMaterializationService.AgentWorkspaceFilePayloadMaterializationResponse response =
                materializationService.materialize(
                        new AgentWorkspaceFilePayloadMaterializationService.AgentWorkspaceFilePayloadMaterializationRequest(
                                null,
                                RUN_ID,
                                "worker-write",
                                "10",
                                "20",
                                "30",
                                "workspace.file.write",
                                "WRITE",
                                "graph-file-worker",
                                "workspace-file-write.v1",
                                "docs/worker-note.md",
                                content,
                                null,
                                false,
                                null,
                                4096,
                                Duration.ofMinutes(15)
                        )
                );
        assertTrue(response.materialized());
        return response.payloadReference();
    }

    private AgentCommandWorkerLeaseRecord claimLease(AgentCommandWorkerLeaseService leaseService) {
        AgentCommandWorkerLeaseClaimResult result = leaseService.claim(SESSION_ID, RUN_ID,
                new AgentCommandWorkerLeaseClaimRequest(COMMAND_ID, EXECUTOR_ID, 10L, 20L, 30L, 120));
        assertTrue(result.acquired());
        assertNotNull(result.record());
        return result.record();
    }

    private AgentToolActionCommandWorkerReceiptRequest successReceipt(AgentCommandWorkerLeaseRecord lease) {
        return receiptWithTool(lease, "workspace.file.write", 30L);
    }

    private AgentToolActionCommandWorkerReceiptRequest receiptWithTool(AgentCommandWorkerLeaseRecord lease,
                                                                       String toolCode,
                                                                       Long actorId) {
        return new AgentToolActionCommandWorkerReceiptRequest(
                COMMAND_ID,
                9101L,
                9201L,
                EXECUTOR_ID,
                10L,
                20L,
                actorId,
                "SUCCEEDED",
                "EXECUTION_SUCCEEDED",
                true,
                true,
                true,
                true,
                lease.fencingToken(),
                lease.leaseVersion(),
                lease.leaseExpiresAt().toEpochMilli(),
                "ALLOW_CONTROLLED_EXECUTION",
                "workspace-file-worker-policy.v1",
                List.of(),
                30,
                4096,
                "MINIO_OBJECT",
                "agent-artifact:run-file-receipt/receipt-001",
                true,
                null,
                "audit-file-worker-001",
                toolCode,
                "agent-file-worker",
                "EXECUTION_RESULT",
                "文件工具已写回低敏执行事实。",
                List.of("确认 artifact grant 后再读取结果正文"),
                "file-worker:cmd-file-001:execution-succeeded"
        );
    }

    private record TestFixture(
            InMemoryAgentToolActionPayloadStore payloadStore,
            InMemoryAgentRuntimeEventProjectionStore projectionStore,
            InMemoryAgentToolActionWorkerReceiptIndexStore indexStore,
            AgentCommandWorkerLeaseService leaseService,
            AgentWorkspaceFileWorkerReceiptService service
    ) {
    }
}
