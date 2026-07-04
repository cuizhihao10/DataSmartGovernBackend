/**
 * @Author : Cui
 * @Date: 2026/07/05 02:36
 * @Description DataSmart Govern Backend - AgentToolActionArtifactObjectStoreRagProbeServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionArtifactBodyReadGrantRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionArtifactBodyReadGrantResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionArtifactObjectStoreProbeRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionArtifactObjectStoreProbeResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionCommandWorkerReceiptRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RAG answer artifact 对象存储探测测试。
 *
 * <p>这组测试从通用命令 artifact probe 测试中拆出来，原因有两点：
 * 第一，RAG 查询是“只读智能检索/生成”场景，不能被误判成命令执行副作用；
 * 第二，RAG answer、compressedContext、citation snippet 都属于正文级高敏内容，Java 控制面只能保存
 * artifactReference、hash、计数、策略版本等低敏事实，真正正文读取必须继续经过 grant、对象存储 probe 与 final-check。
 *
 * <p>因此本测试只验证 RAG 专属安全边界：对象存储 adapter 可以在服务端读取少量 sample 做存在性和指纹校验，
 * 但 HTTP 响应、runtime event、审计视图不能返回 answer 正文、bucket/key、签名 URL 或 token。
 */
class AgentToolActionArtifactObjectStoreRagProbeServiceTest {

    private static final String RAG_SESSION_ID = "session-rag-001";
    private static final String RAG_RUN_ID = "run-rag-001";
    private static final String RAG_COMMAND_ID = "cmd-rag-001";
    private static final String RAG_ARTIFACT_REFERENCE = "agent-artifact:run-rag-001/cmd-rag-001/rag-answer";

    @Test
    void shouldProbeRagAnswerArtifactAfterRagGrantWithoutReturningAnswerBody() throws JsonProcessingException {
        InMemoryAgentRuntimeEventProjectionStore projectionStore =
                new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        InMemoryAgentToolActionArtifactBodyReadGrantStore grantStore = grantStore();
        appendRagCommandWorkerReceipt(projectionStore);
        AgentToolActionArtifactBodyReadGrantResponse grant = issueRagGrant(projectionStore, grantStore);
        AgentToolActionArtifactObjectStoreProbeService service =
                probeService(projectionStore, grantStore,
                        inMemoryClient("RAG_ANSWER_SECRET_BODY should stay inside object store".getBytes(StandardCharsets.UTF_8)));

        AgentToolActionArtifactObjectStoreProbeResponse response = service.probe(
                ragProbeRequest(grant.grantDecisionReference(), 4096),
                projectOwnerContext(List.of(20L))
        );

        /*
         * RAG 查询结果虽然由 worker 生成，但它是只读查询摘要，不是 shell/file/db 写入类副作用。
         * 这里断言 receiptOutcome、toolCode、readPurpose 与 artifactReferenceType 必须同时匹配 RAG 专属契约，
         * 避免普通命令 artifact 的授权规则被误套到 RAG 答案正文上。
         */
        assertTrue(response.probeAllowed());
        assertEquals("OBJECT_STORE_PROBE_VERIFIED_NO_BODY_RETURNED", response.decision());
        assertEquals(RAG_COMMAND_ID, response.commandId());
        assertEquals(RAG_ARTIFACT_REFERENCE, response.artifactReference());
        assertEquals("AGENT_RAG_ANSWER_ARTIFACT", response.artifactReferenceType());
        assertEquals("RAG_ANSWER_VIEW", response.readPurpose());
        assertEquals("RAG_QUERY_COMPLETED", response.receiptOutcome());
        assertEquals("knowledge.rag.query", response.toolCode());
        assertTrue(response.objectStoreProbeExecuted());
        assertTrue(response.objectAvailable());
        assertNotNull(response.sampleSha256Fingerprint());
        assertTrue(response.evidenceCodes().contains("RAG_READ_ONLY_ANSWER_ARTIFACT_ELIGIBLE"));
        assertTrue(response.evidenceCodes().contains("OBJECT_STORE_ADAPTER_BOUNDARY_USED"));
        assertTrue(response.evidenceCodes().contains("SAMPLE_BYTES_NOT_RETURNED"));
        assertFalse(response.bodyContentReturned());
        assertFalse(response.signedUrlIssued());
        assertFalse(response.bearerTokenIssued());

        /*
         * 对象存储 probe 可以读取 sample 做指纹，但 HTTP 响应不能返回 RAG answer 正文。
         * 这条测试把 RAG_ANSWER_SECRET_BODY 放进 fake object sample，确保它只用于 sample hash，
         * 不会穿透到 Java 控制面响应、事件或审计可见字段。
         */
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(response);
        assertFalse(json.contains("RAG_ANSWER_SECRET_BODY"));
        assertFalse(json.contains("compressedContext"));
        assertFalse(json.contains("sourceUri"));
        assertFalse(json.contains("https://"));
        assertFalse(json.contains("bucketName"));
        assertFalse(json.contains("objectKey"));
    }

    private AgentToolActionArtifactObjectStoreProbeRequest ragProbeRequest(String grantDecisionReference,
                                                                           Integer requestedProbeBytes) {
        return new AgentToolActionArtifactObjectStoreProbeRequest(
                RAG_COMMAND_ID,
                RAG_ARTIFACT_REFERENCE,
                "AGENT_RAG_ANSWER_ARTIFACT",
                grantDecisionReference,
                "RAG_ANSWER_VIEW",
                "OBJECT_STORE_BODY_READ_AFTER_STORE_POLICY",
                128 * 1024,
                requestedProbeBytes,
                "10",
                "20",
                null,
                RAG_RUN_ID,
                RAG_SESSION_ID,
                "knowledge.rag.query",
                "agent-runtime"
        );
    }

    private AgentToolActionArtifactBodyReadGrantResponse issueRagGrant(
            InMemoryAgentRuntimeEventProjectionStore projectionStore,
            AgentToolActionArtifactBodyReadGrantStore grantStore) {
        return bodyReadGrantService(projectionStore, grantStore).grantBodyRead(
                new AgentToolActionArtifactBodyReadGrantRequest(
                        RAG_COMMAND_ID,
                        RAG_ARTIFACT_REFERENCE,
                        "AGENT_RAG_ANSWER_ARTIFACT",
                        "RAG_ANSWER_VIEW",
                        "OBJECT_STORE_BODY_READ_AFTER_STORE_POLICY",
                        128 * 1024,
                        "10",
                        "20",
                        null,
                        RAG_RUN_ID,
                        RAG_SESSION_ID,
                        "knowledge.rag.query",
                        "agent-runtime"
                ),
                projectOwnerContext(List.of(20L))
        );
    }

    private AgentToolActionArtifactObjectStoreProbeService probeService(
            InMemoryAgentRuntimeEventProjectionStore projectionStore,
            AgentToolActionArtifactBodyReadGrantStore grantStore,
            AgentToolActionArtifactObjectStoreClient objectStoreClient) {
        return new AgentToolActionArtifactObjectStoreProbeService(
                bodyReadGrantService(projectionStore, grantStore),
                new AgentToolActionArtifactBodyReadGrantVerificationService(grantStore),
                objectStoreClient
        );
    }

    private AgentToolActionArtifactBodyReadGrantService bodyReadGrantService(
            InMemoryAgentRuntimeEventProjectionStore projectionStore,
            AgentToolActionArtifactBodyReadGrantStore grantStore) {
        AgentToolActionArtifactAccessAuthorizationService metadataAuthorizationService =
                new AgentToolActionArtifactAccessAuthorizationService(
                        projectionStore,
                        new AgentRuntimeEventProjectionAccessSupport()
                );
        return new AgentToolActionArtifactBodyReadGrantService(
                metadataAuthorizationService,
                new AgentToolActionArtifactBodyReadGrantRecordService(grantStore)
        );
    }

    private InMemoryAgentToolActionArtifactBodyReadGrantStore grantStore() {
        return new InMemoryAgentToolActionArtifactBodyReadGrantStore(100);
    }

    private AgentToolActionArtifactObjectStoreClient inMemoryClient(byte[] sampleBytes) {
        return command -> new AgentToolActionArtifactObjectStoreProbeSample(
                true,
                true,
                "text/plain; charset=utf-8",
                (long) sampleBytes.length,
                Arrays.copyOf(sampleBytes, Math.min(sampleBytes.length, command.maxProbeBytes())),
                sampleBytes.length > command.maxProbeBytes(),
                "object-version:sha256:rag-test",
                List.of("IN_MEMORY_TEST_RAG_OBJECT_FOUND"),
                List.of(),
                List.of("测试 adapter 只用于 RAG answer artifact 单元测试；生产应替换为真实 MinIO/S3-compatible adapter。")
        );
    }

    private void appendRagCommandWorkerReceipt(InMemoryAgentRuntimeEventProjectionStore projectionStore) {
        AgentToolActionWorkerReceiptIndexService indexService =
                new AgentToolActionWorkerReceiptIndexService(
                        new InMemoryAgentToolActionWorkerReceiptIndexStore(100));
        AgentToolActionCommandWorkerReceiptService receiptService =
                new AgentToolActionCommandWorkerReceiptService(
                        projectionStore,
                        indexService,
                        new AgentCommandWorkerLeaseService(new InMemoryAgentCommandWorkerLeaseStore())
                );

        receiptService.receive(RAG_SESSION_ID, RAG_RUN_ID, "trace-rag-object-store-probe", ragReceiptRequest());
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

    private AgentRuntimeEventQueryAccessContext projectOwnerContext(List<Long> authorizedProjectIds) {
        return new AgentRuntimeEventQueryAccessContext(
                10L,
                30L,
                "PROJECT_OWNER",
                "trace-rag-object-store-probe-test",
                "PROJECT",
                authorizedProjectIds
        );
    }
}
