/**
 * @Author : Cui
 * @Date: 2026/08/19 21:30
 * @Description DataSmart Govern Backend - AgentToolExecutionAuditViewSerializationTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证公开工具审计只返回低敏摘要，内部参数不会随普通 HTTP 响应序列化。 */
class AgentToolExecutionAuditViewSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void shouldSerializeOnlyLowSensitiveToolPlanSummary() throws Exception {
        AgentToolExecutionAuditView view = new AgentToolExecutionAuditView(
                "audit-1",
                "session-1",
                "run-1",
                "binding-1",
                "sync.task.run",
                "HTTP",
                "data-sync",
                "/internal/sync/tasks",
                100L,
                10L,
                20L,
                null,
                "actor-1",
                "LOW",
                "AUTOMATIC",
                false,
                false,
                true,
                List.of("RUN"),
                "执行同步任务",
                Map.of(
                        "syncMode", "FULL",
                        "jdbcUrl", "jdbc:postgresql://prod.example/secret",
                        "sql", "select password from private_table",
                        "credentialRef", "secret://prod"
                ),
                Map.of(
                        "sensitiveArgumentNames", List.of("jdbcUrl", "sql", "credentialRef"),
                        "approvalPolicy", "INTERNAL_ONLY"
                ),
                Map.of(
                        "missingFields", List.of("credentialRef"),
                        "invalidFields", List.of("sql"),
                        "passed", false
                ),
                "PLANNED",
                "trace-1",
                "等待执行",
                null,
                "approval comment contains secret",
                null,
                null,
                null,
                null,
                null,
                LocalDateTime.of(2026, 8, 19, 21, 0),
                LocalDateTime.of(2026, 8, 19, 21, 0)
        );

        String json = objectMapper.writeValueAsString(view);

        assertTrue(json.contains("argumentFields"));
        assertTrue(json.contains("argumentCount"));
        assertTrue(json.contains("sensitiveArgumentCount"));
        assertTrue(json.contains("governanceHintKeys"));
        assertTrue(json.contains("parameterValidationSummary"));
        assertTrue(json.contains("syncMode"));
        assertFalse(json.contains("jdbcUrl"));
        assertFalse(json.contains("jdbc:postgresql://prod.example/secret"));
        assertFalse(json.contains("select password from private_table"));
        assertFalse(json.contains("secret://prod"));
        assertFalse(json.contains("INTERNAL_ONLY"));
        assertFalse(json.contains("approval comment contains secret"));
        assertFalse(json.contains("missingFields"));
        assertFalse(json.contains("credentialRef"));
    }

    /**
     * 派生公开字段必须保持只读：服务端回执可在内部写入脱敏占位符，但不能把原始备注重新序列化出去。
     */
    @Test
    void shouldReadRedactedApprovalPresenceMarkerWithoutSerializingTheMarker() throws Exception {
        AgentToolExecutionAuditView original = new AgentToolExecutionAuditView(
                "audit-marker", "session-marker", "run-marker", "binding-marker", "sync.task.run", "HTTP",
                "data-sync", "/internal/sync", 1L, 1L, 1L, null, "actor", "LOW", "HUMAN_APPROVAL",
                true, false, true, List.of("RUN"), "reason", Map.of(), Map.of(), Map.of(), "SUCCEEDED",
                "trace", "done", "operator", "original secret comment", null, null, null, null, null,
                LocalDateTime.of(2026, 8, 20, 1, 0), LocalDateTime.of(2026, 8, 20, 1, 0));
        Map<String, Object> shape = objectMapper.convertValue(original, Map.class);
        shape.put("approvalComment", "__PRESENT_BUT_REDACTED__");

        AgentToolExecutionAuditView restored = objectMapper.convertValue(shape, AgentToolExecutionAuditView.class);

        assertTrue(restored.approvalCommentPresent());
        assertEquals("__PRESENT_BUT_REDACTED__", restored.approvalComment());
        String restoredJson = objectMapper.writeValueAsString(restored);
        assertTrue(restoredJson.contains("approvalCommentPresent"));
        assertFalse(restoredJson.contains("PRESENT_BUT_REDACTED"));
    }
}
