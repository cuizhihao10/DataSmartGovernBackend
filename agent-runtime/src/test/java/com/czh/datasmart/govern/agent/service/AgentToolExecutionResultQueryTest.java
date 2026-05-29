/**
 * @Author : Cui
 * @Date: 2026/05/27 23:58
 * @Description DataSmart Govern Backend - AgentToolExecutionResultQueryTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionResultView;
import com.czh.datasmart.govern.agent.event.NoopAgentToolExecutionEventPublisher;
import com.czh.datasmart.govern.agent.config.AgentRuntimeProperties;
import com.czh.datasmart.govern.agent.model.AgentRunState;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionMode;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionState;
import com.czh.datasmart.govern.agent.model.AgentToolRiskLevel;
import com.czh.datasmart.govern.agent.model.WorkspaceIsolationLevel;
import com.czh.datasmart.govern.agent.service.audit.AgentToolExecutionAuditMemoryStore;
import com.czh.datasmart.govern.agent.service.audit.AgentToolExecutionAuditRecord;
import com.czh.datasmart.govern.agent.service.session.AgentRunRecord;
import com.czh.datasmart.govern.agent.service.session.AgentSessionMemoryStore;
import com.czh.datasmart.govern.agent.service.session.AgentSessionRecord;
import com.czh.datasmart.govern.agent.service.tool.AgentToolExecutionOutputStore;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent 工具执行结果查询测试。
 *
 * <p>该测试独立于 `AgentSessionServiceTest`，避免主会话测试继续膨胀。
 * 本文件只固定一个契约：工具执行结果查询是只读快照，应该返回审计状态以及成功执行后保存的安全输出。
 * Python AI Runtime 后续会依赖这个契约把 Java 工具结果转换为模型第二轮 tool result message。</p>
 */
class AgentToolExecutionResultQueryTest {

    @Test
    void getResultShouldReturnAuditAndStoredOutputWithoutExecutingAgain() {
        AgentToolExecutionAuditMemoryStore auditStore = new AgentToolExecutionAuditMemoryStore();
        AgentToolExecutionAuditService auditService = new AgentToolExecutionAuditService(
                auditStore,
                new NoopAgentToolExecutionEventPublisher()
        );
        AgentToolExecutionOutputStore outputStore = new AgentToolExecutionOutputStore();
        AgentToolExecutionService executionService = new AgentToolExecutionService(
                auditService,
                List.of(),
                null,
                outputStore
        );
        AgentToolExecutionAuditRecord record = succeededAuditRecord();
        auditService.succeedExecution(record, "工具执行成功", "工具执行成功，输出字段: datasourceId,tableCount");
        auditStore.saveAll(List.of(record));
        outputStore.save(
                new AgentToolExecutionOutputStore.AgentToolExecutionAuditSnapshot(
                        record.getSessionId(),
                        record.getRunId(),
                        record.getAuditId(),
                        record.getToolCode()
                ),
                Map.of("datasourceId", 1001L, "tableCount", 8)
        );

        AgentToolExecutionResultView result = executionService.getResult(
                record.getSessionId(),
                record.getRunId(),
                record.getAuditId()
        );

        assertEquals("SUCCEEDED", result.audit().state());
        assertEquals(1001L, result.output().get("datasourceId"));
        assertEquals(8, result.output().get("tableCount"));
        assertTrue(result.audit().outputSummary().contains("datasourceId"));
    }

    @Test
    void listRunResultsShouldReturnAllAuditSnapshotsInOneQuery() {
        AgentToolExecutionAuditMemoryStore auditStore = new AgentToolExecutionAuditMemoryStore();
        AgentToolExecutionAuditService auditService = new AgentToolExecutionAuditService(
                auditStore,
                new NoopAgentToolExecutionEventPublisher()
        );
        AgentToolExecutionOutputStore outputStore = new AgentToolExecutionOutputStore();
        AgentToolExecutionService executionService = new AgentToolExecutionService(
                auditService,
                List.of(),
                null,
                outputStore
        );
        AgentSessionMemoryStore sessionStore = new AgentSessionMemoryStore();
        AgentToolExecutionResultQueryService queryService = new AgentToolExecutionResultQueryService(
                new AgentRuntimeProperties(),
                sessionStore,
                executionService
        );
        AgentSessionRecord session = sessionWithRun();
        sessionStore.save(session);
        AgentToolExecutionAuditRecord success = succeededAuditRecord();
        auditService.succeedExecution(success, "工具执行成功", "工具执行成功，输出字段: datasourceId,tableCount");
        AgentToolExecutionAuditRecord planned = plannedAuditRecord();
        auditStore.saveAll(List.of(success, planned));
        outputStore.save(
                new AgentToolExecutionOutputStore.AgentToolExecutionAuditSnapshot(
                        success.getSessionId(),
                        success.getRunId(),
                        success.getAuditId(),
                        success.getToolCode()
                ),
                Map.of("datasourceId", 1001L, "tableCount", 8)
        );

        List<AgentToolExecutionResultView> results = queryService.listRunToolExecutionResults(
                success.getSessionId(),
                success.getRunId()
        );

        assertEquals(2, results.size());
        assertEquals("SUCCEEDED", results.getFirst().audit().state());
        assertEquals(8, results.getFirst().output().get("tableCount"));
        assertEquals("PLANNED", results.get(1).audit().state());
        assertTrue(results.get(1).output().isEmpty());
    }

    private AgentToolExecutionAuditRecord succeededAuditRecord() {
        AgentToolExecutionAuditRecord record = new AgentToolExecutionAuditRecord(
                "atea-query-001",
                "session-query-001",
                "run-query-001",
                "binding-query-001",
                "datasource.metadata.read",
                "INTERNAL_API",
                "datasource-management",
                "/metadata",
                1001L,
                10L,
                20L,
                30L,
                "actor-query",
                AgentToolRiskLevel.LOW.name(),
                AgentToolExecutionMode.SYNC.name(),
                false,
                true,
                true,
                List.of("READ"),
                AgentToolExecutionState.PLANNED,
                "trace-query",
                "工具计划已生成。",
                LocalDateTime.now()
        );
        return record;
    }

    private AgentToolExecutionAuditRecord plannedAuditRecord() {
        return new AgentToolExecutionAuditRecord(
                "atea-query-002",
                "session-query-001",
                "run-query-001",
                "binding-query-002",
                "quality.rule.suggest",
                "INTERNAL_API",
                "data-quality",
                "/rules/suggestions",
                1001L,
                10L,
                20L,
                30L,
                "actor-query",
                AgentToolRiskLevel.MEDIUM.name(),
                AgentToolExecutionMode.SYNC.name(),
                false,
                true,
                true,
                List.of("READ"),
                AgentToolExecutionState.PLANNED,
                "trace-query",
                "工具计划已生成，等待执行。",
                LocalDateTime.now()
        );
    }

    private AgentSessionRecord sessionWithRun() {
        AgentSessionRecord session = new AgentSessionRecord(
                "session-query-001",
                10L,
                20L,
                30L,
                "actor-query",
                "PYTHON_AI_RUNTIME",
                "批量查询工具结果",
                WorkspaceIsolationLevel.PROJECT,
                "tenant:10:project:20",
                LocalDateTime.now()
        );
        session.addRun(new AgentRunRecord(
                "run-query-001",
                "session-query-001",
                AgentRunState.PLANNING,
                "AGENT_REASONING",
                "批量查询工具结果",
                true,
                false,
                List.of(),
                Map.of(),
                LocalDateTime.now(),
                "Run 已创建"
        ));
        return session;
    }
}
