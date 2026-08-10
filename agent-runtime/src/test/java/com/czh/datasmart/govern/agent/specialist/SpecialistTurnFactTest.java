/**
 * @Author : Cui
 * @Date: 2026/08/05 00:00
 * @Description DataSmart Govern Backend - SpecialistTurnFactTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.specialist;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 专业 Agent turn 事实领域模型测试。
 *
 * <p>这里优先验证最靠近数据边界的规则：身份字段不能缺失，引用必须是低敏定位符，摘要不能携带高敏正文，
 * 时间关系必须合理，查询范围不能缺少租户/应用/项目/普通用户 actor。领域层先阻断一遍，JDBC 和 HTTP 层才不需要
 * 各自重新实现一套不一致的敏感字段规则。</p>
 */
class SpecialistTurnFactTest {

    /** 正常事实会把 role/status 规范化，并根据起止时间补齐耗时。 */
    @Test
    void shouldNormalizeBusinessFieldsAndCalculateDuration() {
        SpecialistTurnFact fact = new SpecialistTurnFact(
                "user-a", 10L, 10010L, 20L, "session-a", "run-a", "turn-a", "idem-a",
                "knowledge-agent", "knowledge_agent", null, "running", "metadata checked",
                "provider-call-1", "gpt-5.6-sol", List.of("tool.summary:1", "tool.summary:1"),
                List.of("rag.case:42"), null,
                Instant.parse("2026-08-05T00:00:00Z"),
                Instant.parse("2026-08-05T00:00:01Z"),
                Instant.parse("2026-08-05T00:00:00Z"),
                Instant.parse("2026-08-05T00:00:01Z")
        );

        assertEquals("KNOWLEDGE_AGENT", fact.role());
        assertEquals("RUNNING", fact.status());
        assertEquals(1000L, fact.durationMillis());
        assertEquals(List.of("tool.summary:1"), fact.toolActivitySummaryRefs());
    }

    /** 摘要中出现 prompt、SQL 或模型输出正文等标志时必须在进入 Store 前拒绝。 */
    @Test
    void shouldRejectSensitiveSummaryContent() {
        assertThrows(IllegalArgumentException.class, () -> factWithSummary("SELECT password FROM users"));
        assertThrows(IllegalArgumentException.class, () -> factWithSummary("model output: raw answer"));
        assertThrows(IllegalArgumentException.class, () -> factWithSummary("chain of thought details"));
    }

    /** 引用字段只能是可审计定位符，不能携带 JSON、空白或任意正文。 */
    @Test
    void shouldRejectUnsafeReferences() {
        assertThrows(IllegalArgumentException.class, () -> new SpecialistTurnFact(
                "user-a", 10L, 10010L, 20L, "session-a", "run-a", "turn-a", "idem-a",
                "knowledge-agent", "KNOWLEDGE_AGENT", null, "SUCCEEDED", "safe",
                null, null, List.of("tool summary with spaces"), List.of(), 1L,
                null, null, null, null
        ));
    }

    /** 普通用户范围必须带 actor，且对象归属同时匹配租户、应用和项目。 */
    @Test
    void shouldEnforceQueryScopeOwnership() {
        assertThrows(IllegalArgumentException.class, () ->
                SpecialistTurnFact.userScope(10L, 10010L, 20L, " "));

        SpecialistTurnFact fact = factWithSummary("safe");
        SpecialistTurnFact.QueryScope scope = SpecialistTurnFact.userScope(10L, 10010L, 20L, "user-a");
        assertTrue(fact.belongsTo(scope));
        assertFalse(fact.belongsTo(SpecialistTurnFact.userScope(10L, 10010L, 21L, "user-a")));
        assertFalse(fact.belongsTo(SpecialistTurnFact.userScope(10L, 10011L, 20L, "user-a")));
        assertFalse(fact.belongsTo(SpecialistTurnFact.userScope(10L, 10010L, 20L, "user-b")));
    }

    /** 同一幂等事实允许更新状态等可变字段，但不能改变用户、范围或 Agent 身份。 */
    @Test
    void shouldCompareImmutableTurnIdentity() {
        SpecialistTurnFact original = factWithSummary("planned");
        SpecialistTurnFact sameIdentity = new SpecialistTurnFact(
                "user-a", 10L, 10010L, 20L, "session-a", "run-a", "turn-a", "idem-a",
                "knowledge-agent", "KNOWLEDGE_AGENT", null, "SUCCEEDED", "finished",
                null, null, List.of(), List.of(), 3L, null, null,
                original.createdAt(), original.updatedAt()
        );
        SpecialistTurnFact differentUser = new SpecialistTurnFact(
                "user-b", 10L, 10010L, 20L, "session-a", "run-a", "turn-a", "idem-a",
                "knowledge-agent", "KNOWLEDGE_AGENT", null, "SUCCEEDED", "finished",
                null, null, List.of(), List.of(), 3L, null, null,
                original.createdAt(), original.updatedAt()
        );

        assertTrue(original.sameIdentity(sameIdentity));
        assertFalse(original.sameIdentity(differentUser));
    }

    /** 创建一条内容安全的测试事实，减少每个测试对长 record 构造器的噪音。 */
    private SpecialistTurnFact factWithSummary(String summary) {
        return new SpecialistTurnFact(
                "user-a", 10L, 10010L, 20L, "session-a", "run-a", "turn-a", "idem-a",
                "knowledge-agent", "KNOWLEDGE_AGENT", null, "PLANNED", summary,
                null, null, List.of(), List.of(), null, null, null,
                Instant.parse("2026-08-05T00:00:00Z"),
                Instant.parse("2026-08-05T00:00:00Z")
        );
    }
}
