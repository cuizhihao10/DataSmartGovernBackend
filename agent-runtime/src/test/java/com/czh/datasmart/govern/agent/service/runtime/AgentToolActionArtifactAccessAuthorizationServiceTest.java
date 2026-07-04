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
    private static final String RAG_SESSION_ID = "session-rag-001";
    private static final String RAG_RUN_ID = "run-rag-001";
    private static final String RAG_COMMAND_ID = "cmd-rag-001";
    private static final String RAG_ARTIFACT_REFERENCE = "agent-artifact:run-rag-001/cmd-rag-001/rag-answer";

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
    void shouldAuthorizeMetadataOnlyForRagReadOnlyAnswerArtifact() throws JsonProcessingException {
        InMemoryAgentRuntimeEventProjectionStore projectionStore =
                new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        appendRagCommandWorkerReceipt(projectionStore);
        AgentToolActionArtifactAccessAuthorizationService service = service(projectionStore);

        AgentToolActionArtifactAccessAuthorizationResponse response =
                service.authorize(ragAuthorizationRequest("BODY_READ"), projectOwnerContext(List.of(20L)));

        assertTrue(response.authorized());
        assertEquals("METADATA_AUTHORIZED_BODY_REQUIRES_SECONDARY_STORE_AUTHORIZATION", response.decision());
        assertEquals(RAG_COMMAND_ID, response.commandId());
        assertEquals(RAG_ARTIFACT_REFERENCE, response.artifactReference());
        assertEquals("AGENT_RAG_ANSWER_ARTIFACT", response.artifactReferenceType());
        assertEquals("RAG_QUERY_COMPLETED", response.receiptOutcome());
        assertEquals("knowledge.rag.query", response.toolCode());
        assertTrue(response.metadataOnly());
        assertFalse(response.bodyContentGranted());
        assertTrue(response.evidenceCodes().contains("RAG_READ_ONLY_ANSWER_ARTIFACT_ELIGIBLE"));
        assertTrue(response.evidenceCodes().contains("ARTIFACT_BODY_NOT_RETURNED"));

        /*
         * RAG 元数据授权只证明“这个答案产物属于当前 run/session”，不能把 answer、context、
         * 对象存储定位或下载凭据带回 Java 控制面；正文读取必须继续走 grant + final-check。
         */
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(response);
        assertFalse(json.contains("compressedContext"));
        assertFalse(json.contains("sourceUri"));
        assertFalse(json.contains("https://"));
        assertFalse(json.contains("bucketName"));
        assertFalse(json.contains("objectKey"));
        assertFalse(json.contains("bearer"));
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

    private void appendRagCommandWorkerReceipt(InMemoryAgentRuntimeEventProjectionStore projectionStore) {
        AgentToolActionWorkerReceiptIndexService indexService =
                new AgentToolActionWorkerReceiptIndexService(
                        new InMemoryAgentToolActionWorkerReceiptIndexStore(100));
        AgentToolActionCommandWorkerReceiptService receiptService =
                new AgentToolActionCommandWorkerReceiptService(projectionStore, indexService, new AgentCommandWorkerLeaseService(
                        new InMemoryAgentCommandWorkerLeaseStore()));

        receiptService.receive(RAG_SESSION_ID, RAG_RUN_ID, "trace-rag-artifact-access", ragReceiptRequest());
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

    private AgentToolActionCommandWorkerReceiptRequest ragReceiptRequest() {
        return new AgentToolActionCommandWorkerReceiptRequest(
                RAG_COMMAND_ID,
                null,
                null,
                "python-rag-query-worker",
                10L,
                20L,
                30L,
                "SUCCEEDED",
                "RAG_QUERY_COMPLETED",
                true,
                false,
                false,
                false,
                null,
                null,
                null,
                "ALLOW_READ_ONLY_RAG_QUERY",
                "rag-policy.v1",
                List.of(),
                0,
                0,
                "AGENT_RAG_ANSWER_ARTIFACT",
                RAG_ARTIFACT_REFERENCE,
                true,
                null,
                "rag-query:sha256:abcdef123456",
                "knowledge.rag.query",
                "python-ai-runtime-rag",
                "READ_ONLY_QUERY_SUMMARY",
                "RAG 查询已完成低敏回执，答案正文需通过 artifact grant 读取。",
                List.of("通过 artifact grant 读取答案正文。"),
                "rag-worker:run-rag-001:cmd-rag-001"
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

    private AgentToolActionArtifactAccessAuthorizeRequest ragAuthorizationRequest(String requestedAccessMode) {
        return new AgentToolActionArtifactAccessAuthorizeRequest(
                RAG_COMMAND_ID,
                RAG_ARTIFACT_REFERENCE,
                "AGENT_RAG_ANSWER_ARTIFACT",
                requestedAccessMode,
                "10",
                "20",
                null,
                RAG_RUN_ID,
                RAG_SESSION_ID,
                "knowledge.rag.query"
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
