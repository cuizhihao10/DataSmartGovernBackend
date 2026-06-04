/**
 * @Author : Cui
 * @Date: 2026/06/04 23:28
 * @Description DataSmart Govern Backend - JdbcAgentSkillVisibilitySnapshotIndexRecordMapperTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Skill 可见性快照 MySQL 字段映射测试。
 *
 * <p>这组测试不验证 MySQL 方言本身，而是保护“领域 record <-> JDBC 字段”的关键契约。
 * Skill 可见性快照包含 Manifest 指纹、权限事实来源、可见/隐藏数量和多个 JSON 分布字段，
 * 一旦绑定顺序错位，代码仍可能编译通过，但运营报表和前端治理卡片会读到错误事实。
 * 因此我们用轻量 Mockito 测试覆盖最重要的映射方向。</p>
 */
class JdbcAgentSkillVisibilitySnapshotIndexRecordMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void toRecordShouldRestoreCoreFieldsAndAttributesJson() throws SQLException {
        ResultSet resultSet = mock(ResultSet.class);
        Instant createdAt = Instant.parse("2026-06-04T12:00:00Z");
        Instant publishedAt = Instant.parse("2026-06-04T12:00:01Z");
        Instant consumedAt = Instant.parse("2026-06-04T12:00:02Z");
        stubResultSet(resultSet, createdAt, publishedAt, consumedAt);

        AgentRuntimeEventProjectionRecord record =
                JdbcAgentSkillVisibilitySnapshotIndexRecordMapper.toRecord(resultSet, objectMapper);

        assertEquals("identity-skill-001", record.identityKey());
        assertEquals(12L, record.sequence());
        assertEquals(88L, record.replaySequence());
        assertEquals("tenant-a", record.tenantId());
        assertEquals("project-a", record.projectId());
        assertEquals("BOUND_REMOTE_MANIFEST", record.attributes().get("manifestBindingStatus"));
        assertEquals("fingerprint-001", record.attributes().get("manifestFingerprint"));
        assertEquals(List.of("datasource.metadata.read"), record.attributes().get("visibleSkillCodes"));
    }

    @Test
    void bindRecordShouldWriteManifestAndLowSensitiveJsonColumns() throws SQLException {
        PreparedStatement statement = mock(PreparedStatement.class);
        AgentRuntimeEventProjectionRecord record = record();

        JdbcAgentSkillVisibilitySnapshotIndexRecordMapper.bindRecord(statement, record, objectMapper);

        verify(statement).setString(1, "identity-skill-001");
        verify(statement).setString(4, AgentSkillVisibilitySnapshotProjectionService.SKILL_VISIBILITY_EVENT_TYPE);
        verify(statement).setString(37, "BOUND_REMOTE_MANIFEST");
        verify(statement).setString(40, "fingerprint-001");
        verify(statement).setString(46, "[\"datasource.metadata.read\"]");
        ArgumentCaptor<String> attributesJson = ArgumentCaptor.forClass(String.class);
        verify(statement).setString(eq(55), attributesJson.capture());
        assertTrue(attributesJson.getValue().contains("\"manifestFingerprint\":\"fingerprint-001\""));
    }

    @Test
    void bindParametersShouldPreserveStringLongIntegerAndBooleanTypes() throws SQLException {
        PreparedStatement statement = mock(PreparedStatement.class);

        JdbcAgentSkillVisibilitySnapshotIndexRecordMapper.bindParameters(statement, List.of(
                "tenant-a",
                88L,
                100,
                true
        ));

        verify(statement).setString(1, "tenant-a");
        verify(statement).setLong(2, 88L);
        verify(statement).setInt(3, 100);
        verify(statement).setBoolean(4, true);
    }

    private AgentRuntimeEventProjectionRecord record() {
        return new AgentRuntimeEventProjectionRecord(
                "identity-skill-001",
                "agent-runtime-event.v1",
                "python-ai-runtime",
                AgentSkillVisibilitySnapshotProjectionService.SKILL_VISIBILITY_EVENT_TYPE,
                "skill_visibility",
                "Skill 可见性快照已记录",
                "info",
                "tenant-a",
                "project-a",
                "actor-a",
                "request-a",
                "run-a",
                "session-a",
                12L,
                88L,
                Instant.parse("2026-06-04T12:00:00Z"),
                Instant.parse("2026-06-04T12:00:01Z"),
                Instant.parse("2026-06-04T12:00:02Z"),
                Map.of(
                        "snapshotType", "session_skill_visibility",
                        "available", true,
                        "visibleSkillCount", 1,
                        "hiddenSkillCount", 2,
                        "permissionFactSource", "gateway-header",
                        "manifestBindingStatus", "BOUND_REMOTE_MANIFEST",
                        "manifestFingerprint", "fingerprint-001",
                        "visibleSkillCodes", List.of("datasource.metadata.read"),
                        "hiddenAdmissionStatusCounts", Map.of("PERMISSION_DENIED", 2)
                )
        );
    }

    private void stubResultSet(ResultSet resultSet,
                               Instant createdAt,
                               Instant publishedAt,
                               Instant consumedAt) throws SQLException {
        when(resultSet.getString("identity_key")).thenReturn("identity-skill-001");
        when(resultSet.getString("schema_version")).thenReturn("agent-runtime-event.v1");
        when(resultSet.getString("source")).thenReturn("python-ai-runtime");
        when(resultSet.getString("event_type"))
                .thenReturn(AgentSkillVisibilitySnapshotProjectionService.SKILL_VISIBILITY_EVENT_TYPE);
        when(resultSet.getString("stage")).thenReturn("skill_visibility");
        when(resultSet.getString("message")).thenReturn("Skill 可见性快照已记录");
        when(resultSet.getString("severity")).thenReturn("info");
        when(resultSet.getString("tenant_id")).thenReturn("tenant-a");
        when(resultSet.getString("project_id")).thenReturn("project-a");
        when(resultSet.getString("actor_id")).thenReturn("actor-a");
        when(resultSet.getString("request_id")).thenReturn("request-a");
        when(resultSet.getString("run_id")).thenReturn("run-a");
        when(resultSet.getString("session_id")).thenReturn("session-a");
        when(resultSet.getLong("producer_sequence")).thenReturn(12L);
        when(resultSet.getLong("replay_sequence")).thenReturn(88L);
        when(resultSet.wasNull()).thenReturn(false, false);
        when(resultSet.getTimestamp("created_at")).thenReturn(Timestamp.from(createdAt));
        when(resultSet.getTimestamp("published_at")).thenReturn(Timestamp.from(publishedAt));
        when(resultSet.getTimestamp("consumed_at")).thenReturn(Timestamp.from(consumedAt));
        when(resultSet.getString("attributes_json")).thenReturn("""
                {
                  "manifestBindingStatus": "BOUND_REMOTE_MANIFEST",
                  "manifestFingerprint": "fingerprint-001",
                  "visibleSkillCodes": ["datasource.metadata.read"]
                }
                """);
    }
}
