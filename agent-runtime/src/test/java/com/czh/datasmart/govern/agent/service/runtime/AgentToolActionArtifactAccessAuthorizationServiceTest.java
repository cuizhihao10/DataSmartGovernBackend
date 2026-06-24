/**
 * @Author : Cui
 * @Date: 2026/06/24 17:53
 * @Description DataSmart Govern Backend - AgentToolActionArtifactAccessAuthorizationServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentCommandWorkerLeaseClaimRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionArtifactAccessAuthorizationResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionArtifactAccessAuthorizeRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionCommandWorkerReceiptRequest;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 命令执行 artifact 访问预授权服务测试。
 *
 * <p>这组测试保护的是 command durable action 小闭环里的“读结果前最后一道低敏事实校验”：
 * worker receipt 写入后，调用方仍然不能直接凭一个 artifactReference 去下载正文，而必须先证明该引用来自当前租户、
 * 当前项目、当前 run/session 范围内的 command worker receipt。测试中即使构造了真实 worker lease 与 receipt，
 * 也只断言 metadata-only 结果，不读取、不返回、不序列化 stdout/stderr、命令行、签名 URL、token 或对象正文。</p>
 */
class AgentToolActionArtifactAccessAuthorizationServiceTest {

    private static final String SESSION_ID = "session-command";
    private static final String RUN_ID = "run-command";
    private static final String COMMAND_ID = "cmd-worker-001";
    private static final String EXECUTOR_ID = "agent-command-worker";
    private static final String ARTIFACT_REFERENCE = "agent-artifact:run-command/receipt-001";

    @Test
    void shouldAuthorizeMetadataOnlyWhenArtifactMatchesCommandWorkerReceipt() throws JsonProcessingException {
        InMemoryAgentRuntimeEventProjectionStore projectionStore =
                new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        appendSuccessfulCommandWorkerReceipt(projectionStore);
        AgentToolActionArtifactAccessAuthorizationService service = service(projectionStore);

        AgentToolActionArtifactAccessAuthorizationResponse response =
                service.authorize(authorizationRequest("BODY_READ"), projectOwnerContext(List.of(20L)));

        assertTrue(response.authorized());
        assertEquals("METADATA_AUTHORIZED_BODY_REQUIRES_SECONDARY_STORE_AUTHORIZATION", response.decision());
        assertEquals(COMMAND_ID, response.commandId());
        assertEquals(ARTIFACT_REFERENCE, response.artifactReference());
        assertEquals("MINIO_OBJECT", response.artifactReferenceType());
        assertEquals("BODY_READ", response.requestedAccessMode());
        assertTrue(response.metadataOnly());
        assertFalse(response.bodyContentGranted());
        assertTrue(response.matchedReceiptPresent());
        assertNotNull(response.matchedReceiptFingerprint());
        assertEquals(1L, response.replaySequence());
        assertEquals("EXECUTION_SUCCEEDED", response.receiptOutcome());
        assertEquals("10", response.tenantId());
        assertEquals("20", response.projectId());
        assertEquals("30", response.actorId());
        assertEquals(RUN_ID, response.runId());
        assertEquals(SESSION_ID, response.sessionId());
        assertEquals("command.run-program", response.toolCode());
        assertTrue(response.evidenceCodes().contains("COMMAND_WORKER_RECEIPT_MATCHED"));
        assertTrue(response.evidenceCodes().contains("ARTIFACT_BODY_NOT_RETURNED"));
        assertTrue(response.evidenceCodes().contains("SIGNED_URL_NOT_ISSUED"));

        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(response);
        assertFalse(json.contains("stdout"));
        assertFalse(json.contains("stderr"));
        assertFalse(json.contains("commandLine"));
        assertFalse(json.contains("https://"));
        assertFalse(json.contains("internal.example"));
        assertFalse(json.contains("fencingToken"));
        assertFalse(json.contains("cmd-lease:"));
    }

    @Test
    void shouldDenyWhenNoMatchingReceiptExistsInVisibleScope() {
        InMemoryAgentRuntimeEventProjectionStore projectionStore =
                new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        appendSuccessfulCommandWorkerReceipt(projectionStore);
        AgentToolActionArtifactAccessAuthorizationService service = service(projectionStore);

        AgentToolActionArtifactAccessAuthorizationResponse response =
                service.authorize(
                        new AgentToolActionArtifactAccessAuthorizeRequest(
                                "cmd-worker-missing",
                                ARTIFACT_REFERENCE,
                                "MINIO_OBJECT",
                                "METADATA_ONLY",
                                "10",
                                "20",
                                null,
                                RUN_ID,
                                SESSION_ID,
                                "command.run-program"
                        ),
                        projectOwnerContext(List.of(20L))
                );

        assertFalse(response.authorized());
        assertEquals("DENIED_NO_MATCHING_COMMAND_WORKER_RECEIPT", response.decision());
        assertTrue(response.issueCodes().contains("COMMAND_WORKER_RECEIPT_NOT_FOUND_OR_OUT_OF_SCOPE"));
        assertFalse(response.bodyContentGranted());
    }

    @Test
    void projectScopeWithoutAuthorizedProjectsShouldFailClosed() {
        InMemoryAgentRuntimeEventProjectionStore projectionStore =
                new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        appendSuccessfulCommandWorkerReceipt(projectionStore);
        AgentToolActionArtifactAccessAuthorizationService service = service(projectionStore);

        AgentToolActionArtifactAccessAuthorizationResponse response =
                service.authorize(
                        new AgentToolActionArtifactAccessAuthorizeRequest(
                                COMMAND_ID,
                                ARTIFACT_REFERENCE,
                                "MINIO_OBJECT",
                                "METADATA_ONLY",
                                "10",
                                null,
                                null,
                                RUN_ID,
                                SESSION_ID,
                                "command.run-program"
                        ),
                        projectOwnerContext(List.of())
                );

        assertFalse(response.authorized());
        assertEquals("DENIED_NO_MATCHING_COMMAND_WORKER_RECEIPT", response.decision());
        assertTrue(response.evidenceCodes().contains("RUNTIME_EVENT_SCOPE_RESTRICTED"));
    }

    @Test
    void unsafeArtifactReferenceShouldBeRejectedBeforeQueryingProjection() {
        AgentToolActionArtifactAccessAuthorizationService service =
                service(new InMemoryAgentRuntimeEventProjectionStore(10, 100));

        AgentToolActionArtifactAccessAuthorizeRequest request =
                new AgentToolActionArtifactAccessAuthorizeRequest(
                        COMMAND_ID,
                        "https://internal.example.local/minio/raw-output",
                        "MINIO_OBJECT",
                        "BODY_READ",
                        "10",
                        "20",
                        null,
                        RUN_ID,
                        SESSION_ID,
                        "command.run-program"
                );

        assertThrows(PlatformBusinessException.class,
                () -> service.authorize(request, projectOwnerContext(List.of(20L))));
    }

    private void appendSuccessfulCommandWorkerReceipt(InMemoryAgentRuntimeEventProjectionStore projectionStore) {
        AgentToolActionWorkerReceiptIndexService indexService =
                new AgentToolActionWorkerReceiptIndexService(
                        new InMemoryAgentToolActionWorkerReceiptIndexStore(100));
        AgentCommandWorkerLeaseService leaseService =
                new AgentCommandWorkerLeaseService(new InMemoryAgentCommandWorkerLeaseStore());
        AgentCommandWorkerLeaseRecord lease = claimLease(leaseService);
        AgentToolActionCommandWorkerReceiptService receiptService =
                new AgentToolActionCommandWorkerReceiptService(projectionStore, indexService, leaseService);

        receiptService.receive(SESSION_ID, RUN_ID, "trace-artifact-access", successRequest(lease));
    }

    private AgentToolActionCommandWorkerReceiptRequest successRequest(AgentCommandWorkerLeaseRecord lease) {
        return new AgentToolActionCommandWorkerReceiptRequest(
                COMMAND_ID,
                9101L,
                9201L,
                EXECUTOR_ID,
                10L,
                20L,
                30L,
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
                "command-safety-policy.v1",
                List.of(),
                30,
                4096,
                "MINIO_OBJECT",
                ARTIFACT_REFERENCE,
                true,
                null,
                "audit-command-worker-001",
                "command.run-program",
                "task-management-worker",
                "EXECUTION_RESULT",
                "受控命令 worker 已写回低敏执行事实。",
                List.of("确认任务中心状态与 artifact 元数据已经对账。"),
                "command-worker:cmd-worker-001:execution-succeeded"
        );
    }

    private AgentCommandWorkerLeaseRecord claimLease(AgentCommandWorkerLeaseService leaseService) {
        AgentCommandWorkerLeaseClaimResult result = leaseService.claim(
                SESSION_ID,
                RUN_ID,
                new AgentCommandWorkerLeaseClaimRequest(COMMAND_ID, EXECUTOR_ID, 10L, 20L, 30L, 120)
        );
        assertTrue(result.acquired());
        assertNotNull(result.record());
        assertNotNull(result.record().fencingToken());
        return result.record();
    }

    private AgentToolActionArtifactAccessAuthorizeRequest authorizationRequest(String requestedAccessMode) {
        return new AgentToolActionArtifactAccessAuthorizeRequest(
                COMMAND_ID,
                ARTIFACT_REFERENCE,
                "MINIO_OBJECT",
                requestedAccessMode,
                "10",
                "20",
                null,
                RUN_ID,
                SESSION_ID,
                "command.run-program"
        );
    }

    private AgentToolActionArtifactAccessAuthorizationService service(
            InMemoryAgentRuntimeEventProjectionStore projectionStore) {
        return new AgentToolActionArtifactAccessAuthorizationService(
                projectionStore,
                new AgentRuntimeEventProjectionAccessSupport()
        );
    }

    private AgentRuntimeEventQueryAccessContext projectOwnerContext(List<Long> authorizedProjectIds) {
        return new AgentRuntimeEventQueryAccessContext(
                10L,
                30L,
                "PROJECT_OWNER",
                "trace-artifact-access-test",
                "PROJECT",
                authorizedProjectIds
        );
    }
}
