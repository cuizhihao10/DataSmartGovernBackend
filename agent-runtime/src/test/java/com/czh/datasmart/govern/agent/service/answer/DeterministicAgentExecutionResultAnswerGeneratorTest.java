/**
 * @Author : Cui
 * @Date: 2026/07/16 00:00
 * @Description DataSmart Govern Backend - DeterministicAgentExecutionResultAnswerGeneratorTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.answer;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionAuditView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionResultView;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeterministicAgentExecutionResultAnswerGeneratorTest {

    private final DeterministicAgentExecutionResultAnswerGenerator generator =
            new DeterministicAgentExecutionResultAnswerGenerator();

    @Test
    void shouldExplainSuccessfulExecutionWithoutInvokingModelProvider() {
        AgentToolExecutionAuditView runAudit = audit("sync.task.run", "SUCCEEDED");
        AgentExecutionAssistantAnswer answer = generator.generate(
                "COMPLETED", 9, 9, 0, List.of(runAudit), List.of(), List.of());

        assertTrue(answer.content().contains("已提交真实 worker 执行链路"));
        assertEquals("DETERMINISTIC_FALLBACK", answer.mode());
        assertEquals("RESERVED_NOT_INVOKED", answer.modelProviderStatus());
    }

    @Test
    void shouldNotClaimSyncExecutionWhenOnlyMetadataCollectionSucceeded() {
        AgentToolExecutionAuditView metadataAudit = audit("datasource.source.catalog.search", "SUCCEEDED");

        AgentExecutionAssistantAnswer answer = generator.generate(
                "COMPLETED", 1, 1, 0, List.of(metadataAudit), List.of(), List.of());

        assertTrue(answer.content().contains("只完成了数据源目录、连接或元数据等信息收集"));
        assertTrue(answer.content().contains("尚未创建或运行同步任务"));
        assertFalse(answer.content().contains("已经进入真实业务执行链路"));
    }

    @Test
    void shouldTreatScheduledPublishAsCompletedAgentRequest() {
        AgentToolExecutionAuditView publishAudit = audit("sync.task.publish", "SUCCEEDED");
        when(publishAudit.planArguments()).thenReturn(Map.of("syncMode", "SCHEDULED_FULL"));

        AgentExecutionAssistantAnswer answer = generator.generate(
                "COMPLETED", 1, 1, 0, List.of(publishAudit), List.of(), List.of());

        assertTrue(answer.content().contains("调度规则已经生效"));
        assertTrue(answer.content().contains("设定时间自动运行"));
    }

    @Test
    void shouldTreatRealtimePublishAsLongRunningAgentRequest() {
        AgentToolExecutionAuditView publishAudit = audit("sync.task.publish", "SUCCEEDED");
        when(publishAudit.planArguments()).thenReturn(Map.of("syncMode", "CDC_STREAMING"));

        AgentExecutionAssistantAnswer answer = generator.generate(
                "COMPLETED", 1, 1, 0, List.of(publishAudit), List.of(), List.of());

        assertTrue(answer.content().contains("实时通道持续运行"));
        assertTrue(answer.content().contains("没有一次性完成终态"));
    }

    @Test
    void shouldRecognizeOnlyExplicitSuccessfulExecutionTerminalAsCompleted() {
        AgentToolExecutionAuditView statusAudit = audit("sync.execution.status", "SUCCEEDED");
        AgentToolExecutionResultView statusResult = new AgentToolExecutionResultView(
                statusAudit,
                Map.of("terminal", true, "executionState", "SUCCEEDED")
        );

        AgentExecutionAssistantAnswer answer = generator.generate(
                "COMPLETED", 1, 1, 0, List.of(statusAudit), List.of(statusResult), List.of());

        assertTrue(answer.content().contains("同步执行已到达成功终态"));
    }

    @Test
    void shouldExplainPartialFailureFromControlPlaneFacts() {
        AgentToolExecutionAuditView failedAudit = audit("sync.task.draft.save", "FAILED");
        when(failedAudit.auditId()).thenReturn("audit-draft");
        when(failedAudit.errorCode()).thenReturn("SYNC_TOOL_VALIDATION_FAILED");
        when(failedAudit.message()).thenReturn("目标表 public.customer 不存在");
        AgentToolExecutionResultView failedResult = new AgentToolExecutionResultView(
                failedAudit,
                Map.of(
                        "issues", List.of("目标表 public.customer 不存在"),
                        "recommendedActions", List.of("重新选择目标表")
                )
        );
        AgentExecutionAssistantAnswer answer = generator.generate(
                "FAILED", 9, 5, 1, List.of(failedAudit), List.of(failedResult), List.of("RETRY_FAILED_TOOL"));

        assertTrue(answer.content().contains("工具节点执行：成功 5 个，失败 1 个"));
        assertTrue(answer.content().contains("同步任务未成功创建、发布或运行"));
        assertTrue(answer.content().contains("目标表 public.customer 不存在"));
        assertTrue(answer.content().contains("重新选择目标表"));
        assertTrue(answer.content().contains("继续只读诊断"));
    }

    private AgentToolExecutionAuditView audit(String toolCode, String state) {
        AgentToolExecutionAuditView audit = mock(AgentToolExecutionAuditView.class);
        when(audit.toolCode()).thenReturn(toolCode);
        when(audit.state()).thenReturn(state);
        return audit;
    }
}
