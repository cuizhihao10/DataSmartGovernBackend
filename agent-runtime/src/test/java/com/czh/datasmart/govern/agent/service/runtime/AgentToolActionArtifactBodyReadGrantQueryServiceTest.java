/**
 * @Author : Cui
 * @Date: 2026/06/27 00:19
 * @Description DataSmart Govern Backend - AgentToolActionArtifactBodyReadGrantQueryServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.config.AgentArtifactBodyReadGrantStoreProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionArtifactBodyReadGrantQueryResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionArtifactBodyReadGrantRevokeResponse;
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
 * artifact 正文读取 grant fact 查询/撤销服务测试。
 *
 * <p>这组测试保护的是管理面闭环，而不是正文读取链路本身：
 * 1. 查询必须经过租户/项目/actor 数据范围收口；
 * 2. 响应只能包含低敏 grant fact；
 * 3. 缺少 grantDecisionReference/commandId 时禁止无界扫描；
 * 4. 撤销操作者来自可信访问上下文，而不是请求体自报。</p>
 */
class AgentToolActionArtifactBodyReadGrantQueryServiceTest {

    @Test
    void queryShouldReturnScopedLowSensitiveGrantFacts() throws JsonProcessingException {
        AgentToolActionArtifactBodyReadGrantQueryService service = serviceWithRecords();

        AgentToolActionArtifactBodyReadGrantQueryResponse response = service.queryGrants(
                null,
                "cmd-worker-001",
                null,
                null,
                null,
                null,
                "run-command",
                "session-command",
                "command.run-program",
                "ACTIVE",
                20,
                projectOwnerContext(List.of(20L))
        );

        assertEquals(1, response.totalMatched());
        assertEquals("MEMORY", response.storeMode());
        assertEquals("10", response.scopedTenantId());
        assertEquals(List.of("20"), response.authorizedProjectIds());
        assertEquals("artifact-body-grant-decision:sha256:grant-a", response.grants().getFirst().grantDecisionReference());
        assertEquals("LOW_SENSITIVE_GRANT_FACT_ONLY_ARTIFACT_BODY_NOT_RETURNED",
                response.grants().getFirst().payloadPolicy());
        assertTrue(response.evidenceCodes().contains("ARTIFACT_BODY_READ_GRANT_LOW_SENSITIVE_FIELDS_ONLY"));

        /*
         * 序列化检查用于保护“管理视图不是正文下载视图”的边界。
         * 如果后续有人把 bucket/key、URL、prompt、SQL 或工具参数加进 DTO，这里会直接失败。
         */
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(response);
        assertFalse(json.contains("bucket"));
        assertFalse(json.contains("objectKey"));
        assertFalse(json.contains("https://"));
        assertFalse(json.contains("prompt"));
        assertFalse(json.contains("SELECT *"));
        assertFalse(json.contains("arguments"));
        assertFalse(json.contains("stdout"));
        assertFalse(json.contains("stderr"));
    }

    @Test
    void queryShouldRejectUnboundedScan() {
        AgentToolActionArtifactBodyReadGrantQueryService service = serviceWithRecords();

        assertThrows(PlatformBusinessException.class, () -> service.queryGrants(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "ACTIVE",
                20,
                tenantAdminContext()
        ));
    }

    @Test
    void queryShouldReturnEmptyWhenProjectScopeHasNoProjects() {
        AgentToolActionArtifactBodyReadGrantQueryService service = serviceWithRecords();

        AgentToolActionArtifactBodyReadGrantQueryResponse response = service.queryGrants(
                null,
                "cmd-worker-001",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                20,
                projectOwnerContext(List.of())
        );

        assertEquals(0, response.totalMatched());
        assertTrue(response.evidenceCodes().contains("ARTIFACT_BODY_READ_GRANT_PROJECT_SCOPE_EMPTY"));
        assertTrue(response.authorizedProjectIds().isEmpty());
    }

    @Test
    void revokeShouldUseTrustedActorAndKeepLowSensitiveFact() {
        AgentToolActionArtifactBodyReadGrantQueryService service = serviceWithRecords();

        AgentToolActionArtifactBodyReadGrantRevokeResponse response = service.revoke(
                "artifact-body-grant-decision:sha256:grant-a",
                "risk policy changed",
                projectOwnerContext(List.of(20L))
        );

        assertTrue(response.revoked());
        assertEquals("ARTIFACT_BODY_READ_GRANT_REVOKED", response.decision());
        assertNotNull(response.grant().revokedAtEpochMs());
        assertEquals("30", response.grant().revokedBy());
        assertEquals("RISK_POLICY_CHANGED", response.grant().revokeReasonCode());
        assertEquals("REVOKED", response.grant().status());
        assertTrue(response.evidenceCodes().contains("ARTIFACT_BODY_READ_GRANT_REVOKE_OPERATOR_FROM_TRUSTED_HEADER"));
    }

    private AgentToolActionArtifactBodyReadGrantQueryService serviceWithRecords() {
        InMemoryAgentToolActionArtifactBodyReadGrantStore store =
                new InMemoryAgentToolActionArtifactBodyReadGrantStore(100);
        store.save(record(
                "artifact-body-grant-decision:sha256:grant-a",
                "cmd-worker-001",
                "20",
                AgentToolActionArtifactBodyReadGrantStatus.ACTIVE
        ));
        store.save(record(
                "artifact-body-grant-decision:sha256:grant-b",
                "cmd-worker-001",
                "99",
                AgentToolActionArtifactBodyReadGrantStatus.ACTIVE
        ));
        AgentArtifactBodyReadGrantStoreProperties properties = new AgentArtifactBodyReadGrantStoreProperties();
        properties.setStore("memory");
        return new AgentToolActionArtifactBodyReadGrantQueryService(
                new AgentToolActionArtifactBodyReadGrantRecordService(store),
                new AgentRuntimeEventProjectionAccessSupport(),
                properties
        );
    }

    private AgentToolActionArtifactBodyReadGrantRecord record(
            String grantReference,
            String commandId,
            String projectId,
            AgentToolActionArtifactBodyReadGrantStatus status) {
        return new AgentToolActionArtifactBodyReadGrantRecord(
                grantReference,
                commandId,
                "agent-artifact:run-command/receipt-001",
                "MINIO_OBJECT",
                "TASK_RESULT_VIEW",
                "OBJECT_STORE_BODY_READ_AFTER_STORE_POLICY",
                65536,
                "10",
                projectId,
                "30",
                "run-command",
                "session-command",
                "command.run-program",
                "receipt-fingerprint-a",
                101L,
                "EXECUTION_SUCCEEDED",
                1_780_000_000_000L,
                1_780_000_600_000L,
                status,
                null,
                null,
                null
        );
    }

    private AgentRuntimeEventQueryAccessContext projectOwnerContext(List<Long> authorizedProjectIds) {
        return new AgentRuntimeEventQueryAccessContext(
                10L,
                30L,
                "PROJECT_OWNER",
                "trace-artifact-grant-query-test",
                "PROJECT",
                authorizedProjectIds
        );
    }

    private AgentRuntimeEventQueryAccessContext tenantAdminContext() {
        return new AgentRuntimeEventQueryAccessContext(
                10L,
                30L,
                "TENANT_ADMINISTRATOR",
                "trace-artifact-grant-query-test",
                "TENANT",
                List.of()
        );
    }
}
