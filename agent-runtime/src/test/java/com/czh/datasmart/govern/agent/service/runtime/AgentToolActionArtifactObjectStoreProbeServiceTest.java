/**
 * @Author : Cui
 * @Date: 2026/06/26 23:16
 * @Description DataSmart Govern Backend - AgentToolActionArtifactObjectStoreProbeServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentCommandWorkerLeaseClaimRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionArtifactBodyReadGrantRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionArtifactBodyReadGrantResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionArtifactObjectStoreProbeRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionArtifactObjectStoreProbeResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionCommandWorkerReceiptRequest;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * artifact 对象存储探针服务测试。
 *
 * <p>这组测试保护的是 artifact 读取链路从“授权合同”进入“对象存储 adapter”时最容易出错的边界：
 * 服务端可以读取很小 sample 做存在性和指纹校验，但 HTTP 响应绝不能携带 sample 正文、完整 artifact、
 * bucket/key、签名 URL 或内部 endpoint。这样后续把默认禁用 adapter 替换为真实 MinIO adapter 时，
 * 也不会因为实现细节变化破坏低敏控制面约束。</p>
 */
class AgentToolActionArtifactObjectStoreProbeServiceTest {

    private static final String SESSION_ID = "session-command";
    private static final String RUN_ID = "run-command";
    private static final String COMMAND_ID = "cmd-worker-001";
    private static final String EXECUTOR_ID = "agent-command-worker";
    private static final String ARTIFACT_REFERENCE = "agent-artifact:run-command/receipt-001";

    @Test
    void shouldProbeObjectStoreAfterGrantWithoutReturningSampleBody() throws JsonProcessingException {
        InMemoryAgentRuntimeEventProjectionStore projectionStore =
                new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        InMemoryAgentToolActionArtifactBodyReadGrantStore grantStore = grantStore();
        appendSuccessfulCommandWorkerReceipt(projectionStore);
        AgentToolActionArtifactBodyReadGrantResponse grant = issueGrant(projectionStore, grantStore);
        AgentToolActionArtifactObjectStoreProbeService service =
                probeService(projectionStore, grantStore,
                        inMemoryClient("质量报告预览：password=should-never-leak".getBytes(StandardCharsets.UTF_8)));

        AgentToolActionArtifactObjectStoreProbeResponse response = service.probe(
                probeRequest(grant.grantDecisionReference(), 4096),
                projectOwnerContext(List.of(20L))
        );

        assertTrue(response.probeAllowed());
        assertEquals("OBJECT_STORE_PROBE_VERIFIED_NO_BODY_RETURNED", response.decision());
        assertEquals(COMMAND_ID, response.commandId());
        assertEquals(ARTIFACT_REFERENCE, response.artifactReference());
        assertTrue(response.objectStoreProbeExecuted());
        assertTrue(response.objectAvailable());
        assertEquals("text/plain; charset=utf-8", response.contentType());
        assertTrue(response.sampledBytes() > 0);
        assertNotNull(response.sampleSha256Fingerprint());
        assertFalse(response.bodyContentReturned());
        assertFalse(response.signedUrlIssued());
        assertFalse(response.bearerTokenIssued());
        assertTrue(response.evidenceCodes().contains("OBJECT_STORE_ADAPTER_BOUNDARY_USED"));
        assertTrue(response.evidenceCodes().contains("SAMPLE_BYTES_NOT_RETURNED"));

        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(response);
        assertFalse(json.contains("should-never-leak"));
        assertFalse(json.contains("password=should-never-leak"));
        assertFalse(json.contains("https://"));
        assertFalse(json.contains("internal.example"));
        assertFalse(json.contains("bucketName"));
        assertFalse(json.contains("objectKey"));
        assertFalse(json.contains("cmd-lease:"));
        assertFalse(json.contains("fencingToken"));
    }

    @Test
    void shouldReturnUnavailableWhenDefaultObjectStoreClientIsDisabled() {
        InMemoryAgentRuntimeEventProjectionStore projectionStore =
                new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        InMemoryAgentToolActionArtifactBodyReadGrantStore grantStore = grantStore();
        appendSuccessfulCommandWorkerReceipt(projectionStore);
        AgentToolActionArtifactBodyReadGrantResponse grant = issueGrant(projectionStore, grantStore);
        AgentToolActionArtifactObjectStoreProbeService service =
                probeService(projectionStore, grantStore, new DisabledAgentToolActionArtifactObjectStoreClient());

        AgentToolActionArtifactObjectStoreProbeResponse response = service.probe(
                probeRequest(grant.grantDecisionReference(), 4096),
                projectOwnerContext(List.of(20L))
        );

        assertFalse(response.probeAllowed());
        assertEquals("OBJECT_STORE_PROBE_UNAVAILABLE_NO_BODY_RETURNED", response.decision());
        assertFalse(response.objectStoreProbeExecuted());
        assertFalse(response.objectAvailable());
        assertTrue(response.issueCodes().contains("OBJECT_STORE_CLIENT_DISABLED"));
        assertTrue(response.issueCodes().contains("OBJECT_STORE_PROBE_NOT_EXECUTED"));
        assertFalse(response.bodyContentReturned());
    }

    @Test
    void shouldRejectMalformedGrantReferenceBeforeCallingAdapter() {
        AgentToolActionArtifactObjectStoreProbeService service =
                probeService(new InMemoryAgentRuntimeEventProjectionStore(10, 100), grantStore(),
                        inMemoryClient("safe".getBytes(StandardCharsets.UTF_8)));

        assertThrows(PlatformBusinessException.class,
                () -> service.probe(probeRequest("not-a-grant-reference", 4096), projectOwnerContext(List.of(20L))));
    }

    @Test
    void shouldDenyUnstoredGrantReferenceBeforeCallingAdapter() {
        InMemoryAgentRuntimeEventProjectionStore projectionStore =
                new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        InMemoryAgentToolActionArtifactBodyReadGrantStore grantStore = grantStore();
        appendSuccessfulCommandWorkerReceipt(projectionStore);
        AtomicBoolean adapterCalled = new AtomicBoolean(false);
        AgentToolActionArtifactObjectStoreProbeService service = probeService(projectionStore, grantStore, command -> {
            adapterCalled.set(true);
            return inMemoryClient("safe".getBytes(StandardCharsets.UTF_8)).probe(command);
        });

        AgentToolActionArtifactObjectStoreProbeResponse response = service.probe(
                probeRequest("artifact-body-grant-decision:sha256:abcdefabcdefabcdefabcdef", 4096),
                projectOwnerContext(List.of(20L))
        );

        assertFalse(response.probeAllowed());
        assertEquals("DENIED_STORED_BODY_READ_GRANT_NOT_FOUND", response.decision());
        assertFalse(response.objectStoreProbeExecuted());
        assertTrue(response.issueCodes().contains("STORED_BODY_READ_GRANT_NOT_FOUND"));
        assertFalse(adapterCalled.get());
    }

    @Test
    void shouldClipAdapterSampleToHostHardLimit() {
        InMemoryAgentRuntimeEventProjectionStore projectionStore =
                new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        InMemoryAgentToolActionArtifactBodyReadGrantStore grantStore = grantStore();
        appendSuccessfulCommandWorkerReceipt(projectionStore);
        AgentToolActionArtifactBodyReadGrantResponse grant = issueGrant(projectionStore, grantStore);
        byte[] largeSample = new byte[80 * 1024];
        Arrays.fill(largeSample, (byte) 'a');
        AgentToolActionArtifactObjectStoreProbeService service = probeService(projectionStore, grantStore, command ->
                new AgentToolActionArtifactObjectStoreProbeSample(
                        true,
                        true,
                        "text/plain; charset=utf-8",
                        (long) largeSample.length,
                        largeSample,
                        false,
                        "object-version:sha256:test",
                        List.of("IN_MEMORY_TEST_OBJECT_FOUND"),
                        List.of(),
                        List.of()
                )
        );

        AgentToolActionArtifactObjectStoreProbeResponse response = service.probe(
                probeRequest(grant.grantDecisionReference(), 256 * 1024),
                projectOwnerContext(List.of(20L))
        );

        assertTrue(response.probeAllowed());
        assertEquals(64 * 1024, response.probeLimitBytes());
        assertEquals(64 * 1024, response.sampledBytes());
        assertTrue(response.sampleTruncated());
        assertTrue(response.evidenceCodes().contains("OBJECT_STORE_SAMPLE_CLIPPED_BY_HOST_POLICY"));
        assertTrue(response.issueCodes().contains("OBJECT_STORE_SAMPLE_EXCEEDED_HOST_LIMIT"));
    }

    @Test
    void sensitiveArtifactReferenceShouldBeRejected() {
        InMemoryAgentRuntimeEventProjectionStore projectionStore =
                new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        InMemoryAgentToolActionArtifactBodyReadGrantStore grantStore = grantStore();
        appendSuccessfulCommandWorkerReceipt(projectionStore);
        AgentToolActionArtifactBodyReadGrantResponse grant = issueGrant(projectionStore, grantStore);
        AgentToolActionArtifactObjectStoreProbeService service =
                probeService(projectionStore, grantStore, inMemoryClient("safe".getBytes(StandardCharsets.UTF_8)));

        AgentToolActionArtifactObjectStoreProbeRequest request =
                new AgentToolActionArtifactObjectStoreProbeRequest(
                        COMMAND_ID,
                        "https://internal.example/bucket/object-key",
                        "MINIO_OBJECT",
                        grant.grantDecisionReference(),
                        "TASK_RESULT_VIEW",
                        "OBJECT_STORE_BODY_READ_AFTER_STORE_POLICY",
                        128 * 1024,
                        4096,
                        "10",
                        "20",
                        null,
                        RUN_ID,
                        SESSION_ID,
                        "command.run-program",
                        "agent-runtime"
                );

        assertThrows(PlatformBusinessException.class,
                () -> service.probe(request, projectOwnerContext(List.of(20L))));
    }

    private AgentToolActionArtifactObjectStoreClient inMemoryClient(byte[] sampleBytes) {
        return command -> new AgentToolActionArtifactObjectStoreProbeSample(
                true,
                true,
                "text/plain; charset=utf-8",
                (long) sampleBytes.length,
                Arrays.copyOf(sampleBytes, Math.min(sampleBytes.length, command.maxProbeBytes())),
                sampleBytes.length > command.maxProbeBytes(),
                "object-version:sha256:test",
                List.of("IN_MEMORY_TEST_OBJECT_FOUND"),
                List.of(),
                List.of("测试 adapter 只用于单元测试；生产应替换为真实 MinIO/S3-compatible adapter。")
        );
    }

    private AgentToolActionArtifactObjectStoreProbeRequest probeRequest(String grantDecisionReference,
                                                                        Integer requestedProbeBytes) {
        return new AgentToolActionArtifactObjectStoreProbeRequest(
                COMMAND_ID,
                ARTIFACT_REFERENCE,
                "MINIO_OBJECT",
                grantDecisionReference,
                "TASK_RESULT_VIEW",
                "OBJECT_STORE_BODY_READ_AFTER_STORE_POLICY",
                128 * 1024,
                requestedProbeBytes,
                "10",
                "20",
                null,
                RUN_ID,
                SESSION_ID,
                "command.run-program",
                "agent-runtime"
        );
    }

    private AgentToolActionArtifactBodyReadGrantResponse issueGrant(
            InMemoryAgentRuntimeEventProjectionStore projectionStore,
            AgentToolActionArtifactBodyReadGrantStore grantStore) {
        return bodyReadGrantService(projectionStore, grantStore).grantBodyRead(
                new AgentToolActionArtifactBodyReadGrantRequest(
                        COMMAND_ID,
                        ARTIFACT_REFERENCE,
                        "MINIO_OBJECT",
                        "TASK_RESULT_VIEW",
                        "OBJECT_STORE_BODY_READ_AFTER_STORE_POLICY",
                        128 * 1024,
                        "10",
                        "20",
                        null,
                        RUN_ID,
                        SESSION_ID,
                        "command.run-program",
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

    private void appendSuccessfulCommandWorkerReceipt(InMemoryAgentRuntimeEventProjectionStore projectionStore) {
        AgentToolActionWorkerReceiptIndexService indexService =
                new AgentToolActionWorkerReceiptIndexService(
                        new InMemoryAgentToolActionWorkerReceiptIndexStore(100));
        AgentCommandWorkerLeaseService leaseService =
                new AgentCommandWorkerLeaseService(new InMemoryAgentCommandWorkerLeaseStore());
        AgentCommandWorkerLeaseRecord lease = claimLease(leaseService);
        AgentToolActionCommandWorkerReceiptService receiptService =
                new AgentToolActionCommandWorkerReceiptService(projectionStore, indexService, leaseService);

        receiptService.receive(SESSION_ID, RUN_ID, "trace-artifact-object-store-probe", successRequest(lease));
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

    private AgentRuntimeEventQueryAccessContext projectOwnerContext(List<Long> authorizedProjectIds) {
        return new AgentRuntimeEventQueryAccessContext(
                10L,
                30L,
                "PROJECT_OWNER",
                "trace-artifact-object-store-probe-test",
                "PROJECT",
                authorizedProjectIds
        );
    }
}
