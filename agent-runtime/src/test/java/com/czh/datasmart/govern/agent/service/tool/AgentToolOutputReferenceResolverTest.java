/**
 * @Author : Cui
 * @Date: 2026/05/24 23:40
 * @Description DataSmart Govern Backend - AgentToolOutputReferenceResolverTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.tool;

import com.czh.datasmart.govern.agent.model.AgentRunState;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionState;
import com.czh.datasmart.govern.agent.model.WorkspaceIsolationLevel;
import com.czh.datasmart.govern.agent.service.audit.AgentToolExecutionAuditRecord;
import com.czh.datasmart.govern.agent.service.session.AgentRunRecord;
import com.czh.datasmart.govern.agent.service.session.AgentSessionRecord;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具输出引用解析器测试。
 *
 * <p>这里单独测试解析器，是为了把“引用协议”的核心行为固定下来。
 * 后续即使 `quality.rule.suggest`、`task.create.draft` 或更多工具发生变化，
 * 只要它们继续使用该解析器，显式 outputRef 的基本语义就不会漂移。</p>
 */
class AgentToolOutputReferenceResolverTest {

    @Test
    void shouldResolveNestedPathFromExplicitAuditReference() {
        AgentToolExecutionOutputStore outputStore = new AgentToolExecutionOutputStore();
        outputStore.save(
                new AgentToolExecutionOutputStore.AgentToolExecutionAuditSnapshot(
                        "session-001",
                        "run-001",
                        "audit-001",
                        "datasource.metadata.read"
                ),
                Map.of("metadata", Map.of(
                        "tables", List.of(Map.of("tableName", "ods_order"))
                ))
        );
        AgentToolOutputReferenceResolver resolver = new AgentToolOutputReferenceResolver(outputStore);

        Object value = resolver.resolve(context(), Map.of(
                "toolCode", "datasource.metadata.read",
                "auditId", "audit-001",
                "jsonPath", "metadata.tables[0].tableName"
        ), null, null).orElseThrow();

        assertEquals("ods_order", value);
    }

    @Test
    void shouldReturnEmptyWhenReferencePointsToAnotherRun() {
        AgentToolExecutionOutputStore outputStore = new AgentToolExecutionOutputStore();
        outputStore.save(
                new AgentToolExecutionOutputStore.AgentToolExecutionAuditSnapshot(
                        "session-001",
                        "other-run",
                        "audit-001",
                        "datasource.metadata.read"
                ),
                Map.of("metadata", Map.of("tableName", "cross_run_table"))
        );
        AgentToolOutputReferenceResolver resolver = new AgentToolOutputReferenceResolver(outputStore);

        assertTrue(resolver.resolve(context(), Map.of(
                "toolCode", "datasource.metadata.read",
                "auditId", "audit-001",
                "jsonPath", "metadata"
        ), null, null).isEmpty());
    }

    private AgentToolExecutionContext context() {
        AgentSessionRecord session = new AgentSessionRecord(
                "session-001",
                10L,
                20L,
                null,
                "u-001",
                "WEB",
                "测试输出引用",
                WorkspaceIsolationLevel.PROJECT,
                "tenant:10:project:20",
                LocalDateTime.now()
        );
        AgentRunRecord run = new AgentRunRecord(
                "run-001",
                "session-001",
                AgentRunState.PLANNING,
                "AGENT_REASONING",
                "测试输出引用",
                true,
                false,
                List.of(),
                Map.of(),
                LocalDateTime.now(),
                "测试输出引用"
        );
        AgentToolExecutionAuditRecord audit = new AgentToolExecutionAuditRecord(
                "audit-current",
                "session-001",
                "run-001",
                "binding-current",
                "quality.rule.suggest",
                "DATA_QUALITY",
                "data-quality",
                "/quality-rules/suggestions",
                null,
                10L,
                20L,
                null,
                "u-001",
                "MEDIUM",
                "DRAFT_ONLY",
                false,
                true,
                true,
                List.of("GENERATE"),
                "测试输出引用",
                Map.of(),
                Map.of(),
                Map.of("missingFields", List.of()),
                AgentToolExecutionState.PLANNED,
                "trace-ref",
                "测试输出引用",
                LocalDateTime.now()
        );
        return new AgentToolExecutionContext(session, run, audit, Map.of(), "trace-ref");
    }
}
