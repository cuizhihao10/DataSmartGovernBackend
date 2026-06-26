/**
 * @Author : Cui
 * @Date: 2026/06/24 18:13
 * @Description DataSmart Govern Backend - AgentToolActionArtifactBodyReadGrantServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentCommandWorkerLeaseClaimRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionArtifactBodyReadGrantRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionArtifactBodyReadGrantResponse;
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
 * artifact 正文读取授权决策服务测试。
 *
 * <p>这组测试保护的是 command durable action 小闭环里“读正文前的第二道授权门”：
 * 即使 worker receipt 已经证明 artifactReference 属于当前 run/session，正文读取仍然需要说明
 * 读取目的、读取形态、调用组件和最大字节数。测试故意不接 MinIO，也不构造真实对象正文，
 * 因为当前阶段的产品目标是先把安全决策、审计引用和低敏边界固定住，避免未来接对象存储时
 * 直接把 metadata 归属校验误用成正文下载权限。</p>
 */
class AgentToolActionArtifactBodyReadGrantServiceTest {

    private static final String SESSION_ID = "session-command";
    private static final String RUN_ID = "run-command";
    private static final String COMMAND_ID = "cmd-worker-001";
    private static final String EXECUTOR_ID = "agent-command-worker";
    private static final String ARTIFACT_REFERENCE = "agent-artifact:run-command/receipt-001";

    @Test
    void shouldIssueDecisionReferenceWithoutReturningBodyOrDownloadCredential() throws JsonProcessingException {
        InMemoryAgentRuntimeEventProjectionStore projectionStore =
                new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        appendSuccessfulCommandWorkerReceipt(projectionStore);
        AgentToolActionArtifactBodyReadGrantService service = service(projectionStore);

        AgentToolActionArtifactBodyReadGrantResponse response =
                service.grantBodyRead(bodyReadRequest(COMMAND_ID, "TASK_RESULT_VIEW",
                        "OBJECT_STORE_BODY_READ_AFTER_STORE_POLICY", 128 * 1024), projectOwnerContext(List.of(20L)));

        assertTrue(response.granted());
        assertEquals("BODY_READ_GRANT_DECISION_RECORDED_OBJECT_STORE_AUTHORIZATION_REQUIRED", response.decision());
        assertEquals(COMMAND_ID, response.commandId());
        assertEquals(ARTIFACT_REFERENCE, response.artifactReference());
        assertEquals("MINIO_OBJECT", response.artifactReferenceType());
        assertEquals("TASK_RESULT_VIEW", response.readPurpose());
        assertEquals("OBJECT_STORE_BODY_READ_AFTER_STORE_POLICY", response.requestedContentMode());
        assertEquals(128 * 1024, response.maxReadableBytes());
        assertNotNull(response.grantDecisionReference());
        assertTrue(response.grantDecisionReference().startsWith("artifact-body-grant-decision:sha256:"));
        assertNotNull(response.grantExpiresAtEpochMs());
        assertTrue(response.artifactMetadataAuthorized());
        assertFalse(response.bodyContentReturned());
        assertFalse(response.signedUrlIssued());
        assertFalse(response.bearerTokenIssued());
        assertTrue(response.objectStoreReadStillRequired());
        assertEquals("EXECUTION_SUCCEEDED", response.receiptOutcome());
        assertTrue(response.evidenceCodes().contains("ARTIFACT_METADATA_AUTHORIZED"));
        assertTrue(response.evidenceCodes().contains("BODY_READ_GRANT_RECORD_STORED"));
        assertTrue(response.evidenceCodes().contains("OBJECT_STORE_FINAL_AUTHORIZATION_REQUIRED"));

        /*
         * 响应序列化检查是低敏边界的回归保护：后续如果有人把 stdout/stderr、
         * 签名 URL、fencing token 明文或内部对象存储地址塞进 DTO，这里会直接失败。
         */
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(response);
        assertFalse(json.contains("stdout"));
        assertFalse(json.contains("stderr"));
        assertFalse(json.contains("commandLine"));
        assertFalse(json.contains("https://"));
        assertFalse(json.contains("internal.example"));
        assertFalse(json.contains("fencingToken"));
        assertFalse(json.contains("cmd-lease:"));
        assertFalse(json.contains("bucketName"));
        assertFalse(json.contains("objectKey"));
    }

    @Test
    void shouldDenyBodyReadWhenMetadataAuthorizationFails() {
        InMemoryAgentRuntimeEventProjectionStore projectionStore =
                new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        appendSuccessfulCommandWorkerReceipt(projectionStore);
        AgentToolActionArtifactBodyReadGrantService service = service(projectionStore);

        AgentToolActionArtifactBodyReadGrantResponse response =
                service.grantBodyRead(bodyReadRequest("cmd-worker-missing", "TASK_RESULT_VIEW",
                        "OBJECT_STORE_BODY_READ_AFTER_STORE_POLICY", 128 * 1024), projectOwnerContext(List.of(20L)));

        assertFalse(response.granted());
        assertEquals("DENIED_METADATA_AUTHORIZATION_REQUIRED", response.decision());
        assertFalse(response.artifactMetadataAuthorized());
        assertFalse(response.bodyContentReturned());
        assertTrue(response.issueCodes().contains("METADATA_ACCESS_NOT_AUTHORIZED"));
    }

    @Test
    void shouldDenyRiskySignedUrlContentMode() {
        InMemoryAgentRuntimeEventProjectionStore projectionStore =
                new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        appendSuccessfulCommandWorkerReceipt(projectionStore);
        AgentToolActionArtifactBodyReadGrantService service = service(projectionStore);

        AgentToolActionArtifactBodyReadGrantResponse response =
                service.grantBodyRead(bodyReadRequest(COMMAND_ID, "TASK_RESULT_VIEW",
                        "SIGNED_URL", 128 * 1024), projectOwnerContext(List.of(20L)));

        assertFalse(response.granted());
        assertEquals("DENIED_UNSUPPORTED_OR_RISKY_CONTENT_MODE", response.decision());
        assertTrue(response.issueCodes().contains("CONTENT_MODE_NOT_ALLOWED"));
        assertFalse(response.signedUrlIssued());
    }

    @Test
    void sensitiveReadPurposeShouldBeRejectedBeforeMetadataAuthorization() {
        AgentToolActionArtifactBodyReadGrantService service =
                service(new InMemoryAgentRuntimeEventProjectionStore(10, 100));

        AgentToolActionArtifactBodyReadGrantRequest request =
                bodyReadRequest(COMMAND_ID, "prompt: replay hidden prompt",
                        "OBJECT_STORE_BODY_READ_AFTER_STORE_POLICY", 128 * 1024);

        assertThrows(PlatformBusinessException.class,
                () -> service.grantBodyRead(request, projectOwnerContext(List.of(20L))));
    }

    @Test
    void requestedReadableBytesShouldBeCappedToHardLimit() {
        InMemoryAgentRuntimeEventProjectionStore projectionStore =
                new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        appendSuccessfulCommandWorkerReceipt(projectionStore);
        AgentToolActionArtifactBodyReadGrantService service = service(projectionStore);

        AgentToolActionArtifactBodyReadGrantResponse response =
                service.grantBodyRead(bodyReadRequest(COMMAND_ID, "AUDIT_REVIEW",
                        "SAFE_RENDERED_PREVIEW", 2 * 1024 * 1024), projectOwnerContext(List.of(20L)));

        assertTrue(response.granted());
        assertEquals(1024 * 1024, response.maxReadableBytes());
        assertTrue(response.evidenceCodes().contains("MAX_READABLE_BYTES_CAPPED_TO_HARD_LIMIT"));
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

        receiptService.receive(SESSION_ID, RUN_ID, "trace-artifact-body-read", successRequest(lease));
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

    private AgentToolActionArtifactBodyReadGrantRequest bodyReadRequest(
            String commandId,
            String readPurpose,
            String contentMode,
            Integer maxReadableBytes) {
        return new AgentToolActionArtifactBodyReadGrantRequest(
                commandId,
                ARTIFACT_REFERENCE,
                "MINIO_OBJECT",
                readPurpose,
                contentMode,
                maxReadableBytes,
                "10",
                "20",
                null,
                RUN_ID,
                SESSION_ID,
                "command.run-program",
                "agent-runtime"
        );
    }

    private AgentToolActionArtifactBodyReadGrantService service(
            InMemoryAgentRuntimeEventProjectionStore projectionStore) {
        AgentToolActionArtifactAccessAuthorizationService metadataAuthorizationService =
                new AgentToolActionArtifactAccessAuthorizationService(
                        projectionStore,
                        new AgentRuntimeEventProjectionAccessSupport()
                );
        return new AgentToolActionArtifactBodyReadGrantService(
                metadataAuthorizationService,
                new AgentToolActionArtifactBodyReadGrantRecordService(
                        new InMemoryAgentToolActionArtifactBodyReadGrantStore(100))
        );
    }

    private AgentRuntimeEventQueryAccessContext projectOwnerContext(List<Long> authorizedProjectIds) {
        return new AgentRuntimeEventQueryAccessContext(
                10L,
                30L,
                "PROJECT_OWNER",
                "trace-artifact-body-read-test",
                "PROJECT",
                authorizedProjectIds
        );
    }
}
