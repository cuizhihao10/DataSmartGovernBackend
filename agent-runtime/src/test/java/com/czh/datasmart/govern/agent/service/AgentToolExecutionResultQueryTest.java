/**
 * @Author : Cui
 * @Date: 2026/05/27 23:58
 * @Description DataSmart Govern Backend - AgentToolExecutionResultQueryTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionResultView;
import com.czh.datasmart.govern.agent.event.NoopAgentToolExecutionEventPublisher;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionMode;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionState;
import com.czh.datasmart.govern.agent.model.AgentToolRiskLevel;
import com.czh.datasmart.govern.agent.service.audit.AgentToolExecutionAuditMemoryStore;
import com.czh.datasmart.govern.agent.service.audit.AgentToolExecutionAuditRecord;
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
}
