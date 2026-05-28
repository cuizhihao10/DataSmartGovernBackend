/**
 * @Author : Cui
 * @Date: 2026/05/24 23:12
 * @Description DataSmart Govern Backend - TaskCreateDraftToolAdapterTest.java
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * `task.create.draft` 工具适配器测试。
 *
 * <p>测试重点是固定两个生产级边界：</p>
 * <p>1. 工具可以消费前序 `quality.rule.suggest` 输出，自动生成质量扫描任务草稿；</p>
 * <p>2. 工具输出必须声明 draftOnly/sideEffect=NONE，避免未来维护者误以为它已经创建了真实任务。</p>
 */
class TaskCreateDraftToolAdapterTest {

    @Test
    void shouldCreateDraftFromPreviousQualityRuleSuggestionOutput() {
        AgentToolExecutionOutputStore outputStore = new AgentToolExecutionOutputStore();
        outputStore.save(
                new AgentToolExecutionOutputStore.AgentToolExecutionAuditSnapshot(
                        "session-001",
                        "run-001",
                        "audit-quality",
                        "quality.rule.suggest"
                ),
                Map.of("suggestion", qualitySuggestion())
        );
        TaskCreateDraftToolAdapter adapter = adapter(outputStore);

        AgentToolExecutionOutcome outcome = adapter.execute(context(Map.of(
                "objective", "为订单表质量规则生成可审批的扫描任务草稿",
                "priority", "high"
        )));

        assertTrue(outcome.success());
        Map<?, ?> summary = assertInstanceOf(Map.class, outcome.output().get("summary"));
        assertEquals(true, summary.get("draftOnly"));
        assertEquals("NONE", summary.get("sideEffect"));
        assertEquals(true, summary.get("approvalRequired"));
        assertEquals("DATA_QUALITY_SCAN", summary.get("taskType"));
        assertEquals(2, summary.get("sourceSuggestionCount"));

        Map<?, ?> taskDraft = assertInstanceOf(Map.class, outcome.output().get("taskDraft"));
        assertEquals("DATA_QUALITY_SCAN", taskDraft.get("type"));
        assertEquals("HIGH", taskDraft.get("priority"));
        assertEquals("DRAFT_ONLY_NOT_PERSISTED", taskDraft.get("statusPolicy"));
        assertTrue(String.valueOf(taskDraft.get("name")).contains("ods_order"));

        Map<?, ?> params = assertInstanceOf(Map.class, taskDraft.get("params"));
        Map<?, ?> sourceSummary = assertInstanceOf(Map.class, params.get("qualitySuggestionSummary"));
        assertEquals(1001L, sourceSummary.get("datasourceId"));
        assertEquals("ods_order", sourceSummary.get("tableName"));
    }

    @Test
    void shouldFallBackToManualReviewDraftWhenNoSourceSuggestionExists() {
        TaskCreateDraftToolAdapter adapter = adapter(new AgentToolExecutionOutputStore());

        AgentToolExecutionOutcome outcome = adapter.execute(context(Map.of(
                "objective", "请项目负责人复核本次治理建议",
                "maxRetryCount", 99,
                "maxDeferCount", -1
        )));

        assertTrue(outcome.success());
        Map<?, ?> taskDraft = assertInstanceOf(Map.class, outcome.output().get("taskDraft"));
        assertEquals("MANUAL_REVIEW", taskDraft.get("type"));
        assertEquals(20, taskDraft.get("maxRetryCount"));
        assertEquals(0, taskDraft.get("maxDeferCount"));
    }

    @Test
    void shouldCreateDraftFromExplicitSuggestionReferenceWhenMultipleSuggestionsExist() {
        AgentToolExecutionOutputStore outputStore = new AgentToolExecutionOutputStore();
        outputStore.save(
                new AgentToolExecutionOutputStore.AgentToolExecutionAuditSnapshot(
                        "session-001",
                        "run-001",
                        "audit-old-quality",
                        "quality.rule.suggest"
                ),
                Map.of("suggestion", Map.of(
                        "datasourceId", 1001L,
                        "tableName", "old_table",
                        "suggestionCount", 1,
                        "suggestions", List.of(Map.of("name", "old rule"))
                ))
        );
        outputStore.save(
                new AgentToolExecutionOutputStore.AgentToolExecutionAuditSnapshot(
                        "session-001",
                        "run-001",
                        "audit-target-quality",
                        "quality.rule.suggest"
                ),
                Map.of("suggestion", qualitySuggestion())
        );
        TaskCreateDraftToolAdapter adapter = adapter(outputStore);

        AgentToolExecutionOutcome outcome = adapter.execute(context(Map.of(
                "objective", "为指定质量规则建议生成任务草稿",
                "suggestionRef", Map.of(
                        "fromTool", "quality.rule.suggest",
                        "fromAuditId", "audit-target-quality",
                        "path", "$.suggestion"
                )
        )));

        assertTrue(outcome.success());
        Map<?, ?> taskDraft = assertInstanceOf(Map.class, outcome.output().get("taskDraft"));
        assertTrue(String.valueOf(taskDraft.get("name")).contains("ods_order"));
        Map<?, ?> params = assertInstanceOf(Map.class, taskDraft.get("params"));
        Map<?, ?> sourceSummary = assertInstanceOf(Map.class, params.get("qualitySuggestionSummary"));
        assertEquals("ods_order", sourceSummary.get("tableName"));
    }

    private TaskCreateDraftToolAdapter adapter(AgentToolExecutionOutputStore outputStore) {
        return new TaskCreateDraftToolAdapter(
                new TaskCreateDraftRequestFactory(new AgentToolOutputReferenceResolver(outputStore)),
                new TaskCreateDraftPayloadBuilder()
        );
    }

    private AgentToolExecutionContext context(Map<String, Object> planArguments) {
        AgentSessionRecord session = new AgentSessionRecord(
                "session-001",
                10L,
                20L,
                30L,
                "u-001",
                "WEB",
                "创建任务草稿",
                WorkspaceIsolationLevel.PROJECT,
                "tenant:10:project:20",
                LocalDateTime.now()
        );
        AgentRunRecord run = new AgentRunRecord(
                "run-001",
                "session-001",
                AgentRunState.PLANNING,
                "AGENT_REASONING",
                "为订单表质量规则生成可审批的扫描任务草稿",
                true,
                false,
                List.of(),
                Map.of(),
                LocalDateTime.now(),
                "测试任务草稿工具"
        );
        AgentToolExecutionAuditRecord audit = new AgentToolExecutionAuditRecord(
                "audit-task",
                "session-001",
                "run-001",
                "binding-task",
                "task.create.draft",
                "TASK_MANAGEMENT",
                "task-management",
                "/tasks",
                null,
                10L,
                20L,
                30L,
                "u-001",
                "HIGH",
                "APPROVAL_REQUIRED",
                true,
                false,
                false,
                List.of("CREATE"),
                "生成受控任务草稿",
                planArguments,
                Map.of("projectScoped", true),
                Map.of("missingFields", List.of()),
                AgentToolExecutionState.PLANNED,
                "trace-task",
                "测试任务草稿工具",
                LocalDateTime.now()
        );
        return new AgentToolExecutionContext(session, run, audit, run.getVariables(), "trace-task");
    }

    private Map<String, Object> qualitySuggestion() {
        return Map.of(
                "datasourceId", 1001L,
                "tableName", "ods_order",
                "businessGoal", "检查订单主键唯一性和金额有效性",
                "suggestionCount", 2,
                "generationStrategy", "deterministic-metadata-rule-engine-v1",
                "suggestions", List.of(
                        Map.of("name", "ods_order.order_id 唯一性草案", "ruleType", "UNIQUENESS"),
                        Map.of("name", "ods_order.amount 有效性草案", "ruleType", "VALIDITY")
                )
        );
    }
}
