/**
 * @Author : Cui
 * @Date: 2026/05/31 18:16
 * @Description DataSmart Govern Backend - AgentToolAuditPayloadReferenceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Agent 工具审计载荷引用解析测试。
 *
 * <p>payloadReference 是跨 agent-runtime、Kafka、task-management 和 worker 的关键协议。
 * 测试重点是拒绝非白名单协议和未知 payloadKind，避免未来 worker 被错误引用诱导读取不受控资源。</p>
 */
class AgentToolAuditPayloadReferenceTest {

    @Test
    void shouldParseCanonicalPlanArgumentsReference() {
        AgentToolAuditPayloadReference reference = AgentToolAuditPayloadReference.parse(
                "agent-tool-audit://session-001/run-001/audit-001/plan-arguments"
        );

        assertEquals("session-001", reference.sessionId());
        assertEquals("run-001", reference.runId());
        assertEquals("audit-001", reference.auditId());
        assertEquals("plan-arguments", reference.payloadKind());
        assertEquals("agent-tool-audit://session-001/run-001/audit-001/plan-arguments",
                reference.toCanonicalString());
    }

    @Test
    void shouldRejectUnknownProtocol() {
        assertThrows(IllegalArgumentException.class,
                () -> AgentToolAuditPayloadReference.parse("https://example.com/secret"));
    }

    @Test
    void shouldRejectUnknownPayloadKind() {
        assertThrows(IllegalArgumentException.class,
                () -> AgentToolAuditPayloadReference.parse(
                        "agent-tool-audit://session-001/run-001/audit-001/raw-secret"
                ));
    }
}
