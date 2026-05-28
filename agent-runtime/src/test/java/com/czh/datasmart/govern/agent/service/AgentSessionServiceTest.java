/**
 * @Author : Cui
 * @Date: 2026/05/13 23:05
 * @Description DataSmart Govern Backend - AgentSessionServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service;

import com.czh.datasmart.govern.agent.config.AgentRuntimeProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunView;
import com.czh.datasmart.govern.agent.controller.dto.AgentSessionView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionAuditView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionDecisionRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionResultView;
import com.czh.datasmart.govern.agent.controller.dto.BindAgentToolRequest;
import com.czh.datasmart.govern.agent.controller.dto.CreateAgentSessionRequest;
import com.czh.datasmart.govern.agent.controller.dto.StartAgentRunRequest;
import com.czh.datasmart.govern.agent.event.NoopAgentToolExecutionEventPublisher;
import com.czh.datasmart.govern.agent.model.AgentToolType;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionMode;
import com.czh.datasmart.govern.agent.model.AgentToolRiskLevel;
import com.czh.datasmart.govern.agent.model.WorkspaceIsolationLevel;
import com.czh.datasmart.govern.agent.service.session.AgentSessionMemoryStore;
import com.czh.datasmart.govern.agent.service.session.AgentRunStateCoordinator;
import com.czh.datasmart.govern.agent.service.audit.AgentToolExecutionAuditMemoryStore;
import com.czh.datasmart.govern.agent.service.tool.AgentToolAdapter;
import com.czh.datasmart.govern.agent.service.tool.AgentToolExecutionContext;
import com.czh.datasmart.govern.agent.service.tool.AgentToolExecutionGuard;
import com.czh.datasmart.govern.agent.service.tool.AgentToolExecutionOutcome;
import com.czh.datasmart.govern.agent.service.tool.AgentToolExecutionOutputStore;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent 会话服务单元测试。
 *
 * <p>测试不启动 Spring 容器，重点固定会话创建、工具数量、运行并发、终态保护和工具审批等纯业务状态语义。
 */
class AgentSessionServiceTest {

    private AgentRuntimeProperties properties;
    private AgentSessionService service;
    private AgentToolExecutionAuditService auditService;

    @BeforeEach
    void setUp() {
        properties = new AgentRuntimeProperties();
        properties.setMaxToolBindingsPerSession(2);
        properties.setMaxRunsPerSession(3);
        properties.setMaxActiveRunsPerSession(1);
        properties.setToolRegistry(new LinkedHashMap<>());
        properties.getToolRegistry().put("datasource.metadata.read", datasourceMetadataDefinition());
        properties.getToolRegistry().put("quality.rule.suggest", qualityRuleDefinition());
        properties.getToolRegistry().put("task.create", taskDefinition());
        AgentToolRegistryService toolRegistryService = new AgentToolRegistryService(properties);
        auditService = new AgentToolExecutionAuditService(
                new AgentToolExecutionAuditMemoryStore(),
                new NoopAgentToolExecutionEventPublisher()
        );
        AgentToolExecutionService toolExecutionService = new AgentToolExecutionService(
                auditService,
                List.of(metadataReadAdapterForTest()),
                new AgentToolExecutionGuard(),
                new AgentToolExecutionOutputStore()
        );
        AgentRunStateCoordinator runStateCoordinator = new AgentRunStateCoordinator(auditService);
        service = new AgentSessionService(
                properties,
                new AgentSessionMemoryStore(),
                toolRegistryService,
                auditService,
                toolExecutionService,
                runStateCoordinator
        );
    }

    /**
     * 验证会话创建会生成项目级工作空间 key，并保留初始工具绑定。
     */
    @Test
    void createSessionShouldBuildWorkspaceAndToolBindings() {
        AgentSessionView session = service.createSession(new CreateAgentSessionRequest(
                10L,
                20L,
                30L,
                "u-001",
                "web",
                "分析数据源质量问题并生成规则建议",
                WorkspaceIsolationLevel.PROJECT,
                List.of(metadataTool())
        ));

        assertNotNull(session.sessionId());
        assertEquals("ACTIVE", session.state());
        assertEquals("WEB", session.channel());
        assertEquals("tenant:10:project:20", session.workspace().workspaceKey());
        assertEquals(1, session.toolBindings().size());
        assertEquals("datasource.metadata.read", session.toolBindings().getFirst().toolCode());
        assertEquals("LOW", session.toolBindings().getFirst().riskLevel());
        assertEquals("datasource-management", session.toolBindings().getFirst().targetService());
    }

    /**
     * 验证工具数量上限。
     */
    @Test
    void bindToolShouldRejectWhenToolLimitExceeded() {
        AgentSessionView session = service.createSession(new CreateAgentSessionRequest(
                10L,
                20L,
                null,
                "u-001",
                "api",
                "创建带两个工具的会话",
                WorkspaceIsolationLevel.WORKSPACE,
                List.of(metadataTool(), qualityTool())
        ));

        PlatformBusinessException exception = assertThrows(PlatformBusinessException.class,
                () -> service.bindTool(session.sessionId(), taskTool()));
        assertTrue(exception.getMessage().contains("最多绑定 2 个工具"));
    }

    /**
     * 验证单会话同一时间只允许一个未完成运行。
     */
    @Test
    void startRunShouldRejectSecondActiveRun() {
        AgentSessionView session = service.createSession(baseSessionRequest());
        AgentRunView firstRun = service.startRun(session.sessionId(), runRequest("分析这个数据源是否适合做质量规则"), "trace-test");

        assertEquals("PLANNING", firstRun.state());
        assertFalse(firstRun.requireHumanApproval());
        PlatformBusinessException exception = assertThrows(PlatformBusinessException.class,
                () -> service.startRun(session.sessionId(), runRequest("继续生成清洗方案"), "trace-test"));
        assertTrue(exception.getMessage().contains("已有未完成 Agent Run"));
    }

    /**
     * 验证取消运行会进入终态，且不能重复取消。
     */
    @Test
    void cancelRunShouldBeTerminal() {
        AgentSessionView session = service.createSession(baseSessionRequest());
        AgentRunView run = service.startRun(session.sessionId(), runRequest("生成治理方案"), "trace-test");
        AgentRunView cancelled = service.cancelRun(session.sessionId(), run.runId());

        assertEquals("CANCELLED", cancelled.state());
        PlatformBusinessException exception = assertThrows(PlatformBusinessException.class,
                () -> service.cancelRun(session.sessionId(), run.runId()));
        assertTrue(exception.getMessage().contains("已进入终态"));
    }

    /**
     * 验证创建运行时会自动生成工具执行审计计划。
     */
    @Test
    void startRunShouldCreateToolExecutionAuditPlan() {
        AgentSessionView session = service.createSession(new CreateAgentSessionRequest(
                10L,
                20L,
                null,
                "u-001",
                "web",
                "创建包含低风险和高风险工具的会话",
                WorkspaceIsolationLevel.PROJECT,
                List.of(metadataTool(), taskTool())
        ));
        AgentRunView run = service.startRun(session.sessionId(), runRequest("生成治理执行计划"), "trace-audit");

        assertEquals("WAITING_HUMAN", run.state());
        assertTrue(run.requireHumanApproval());
        assertTrue(run.message().contains("高风险") || run.message().contains("审批"));
        var audits = auditService.listByRun(session.sessionId(), run.runId());
        assertEquals(2, audits.size());
        assertTrue(audits.stream().anyMatch(item -> item.toolCode().equals("datasource.metadata.read")
                && item.state().equals("PLANNED")
                && item.traceId().equals("trace-audit")));
        assertTrue(audits.stream().anyMatch(item -> item.toolCode().equals("task.create")
                && item.state().equals("WAITING_APPROVAL")
                && Boolean.TRUE.equals(item.requiresApproval())));
    }

    /**
     * 验证高风险工具计划可以被人工确认或拒绝。
     *
     * <p>确认回到 PLANNED，拒绝进入 SKIPPED，用于保护真实执行前的人工安全闸门。
     */
    @Test
    void toolExecutionApprovalShouldMoveWaitingApprovalPlan() {
        AgentSessionView approvedSession = service.createSession(highRiskSessionRequest("审批通过场景"));
        AgentRunView approvedRun = service.startRun(approvedSession.sessionId(), runRequest("创建治理任务草稿"), "trace-approve");
        AgentToolExecutionAuditView pendingApproval = firstWaitingApproval(approvedSession.sessionId(), approvedRun.runId());

        AgentToolExecutionAuditView approved = service.approveToolExecution(
                approvedSession.sessionId(),
                approvedRun.runId(),
                pendingApproval.auditId(),
                new AgentToolExecutionDecisionRequest("owner-001", "确认先生成任务草稿，后续人工复核后再正式执行")
        );

        assertEquals("PLANNED", approved.state());
        assertEquals("owner-001", approved.approvalOperatorId());
        assertTrue(approved.message().contains("人工确认"));
        assertEquals("PLANNING", findRunView(approvedSession.sessionId(), approvedRun.runId()).state());

        AgentSessionView rejectedSession = service.createSession(highRiskSessionRequest("审批拒绝场景"));
        AgentRunView rejectedRun = service.startRun(rejectedSession.sessionId(), runRequest("创建治理任务草稿"), "trace-reject");
        AgentToolExecutionAuditView pendingReject = firstWaitingApproval(rejectedSession.sessionId(), rejectedRun.runId());

        AgentToolExecutionAuditView rejected = service.rejectToolExecution(
                rejectedSession.sessionId(),
                rejectedRun.runId(),
                pendingReject.auditId(),
                new AgentToolExecutionDecisionRequest("owner-001", "任务影响范围不清晰，暂不允许创建")
        );

        assertEquals("SKIPPED", rejected.state());
        assertEquals("owner-001", rejected.approvalOperatorId());
        assertTrue(rejected.message().contains("人工拒绝"));
        assertEquals("REJECTED", findRunView(rejectedSession.sessionId(), rejectedRun.runId()).state());
    }

    /**
     * 验证低风险工具可以从 PLANNED 进入真实执行结果状态。
     */
    @Test
    void executeToolShouldMovePlannedAuditToSucceeded() {
        AgentSessionView session = service.createSession(baseSessionRequest());
        AgentRunView run = service.startRun(session.sessionId(), runRequest("读取数据源元数据"), "trace-execute");
        AgentToolExecutionAuditView planned = auditService.listByRun(session.sessionId(), run.runId()).getFirst();

        AgentToolExecutionResultView result = service.executeToolExecution(
                session.sessionId(),
                run.runId(),
                planned.auditId(),
                "trace-execute"
        );

        assertEquals("SUCCEEDED", result.audit().state());
        assertEquals(1001L, result.output().get("datasourceId"));
        assertTrue(result.audit().outputSummary().contains("datasourceId"));
    }

    /**
     * 验证工具执行的失败保护。
     */
    @Test
    void executeToolShouldProtectInvalidStateAndMissingAdapter() {
        AgentSessionView waitingSession = service.createSession(highRiskSessionRequest("等待审批时禁止执行"));
        AgentRunView waitingRun = service.startRun(waitingSession.sessionId(), runRequest("尝试绕过审批执行"), "trace-waiting");
        AgentToolExecutionAuditView waitingAudit = firstWaitingApproval(waitingSession.sessionId(), waitingRun.runId());

        PlatformBusinessException waitingException = assertThrows(PlatformBusinessException.class,
                () -> service.executeToolExecution(waitingSession.sessionId(), waitingRun.runId(), waitingAudit.auditId(), "trace-waiting"));
        assertTrue(waitingException.getMessage().contains("等待人工确认"));

        AgentSessionView duplicateSession = service.createSession(baseSessionRequest());
        AgentRunView duplicateRun = service.startRun(duplicateSession.sessionId(), runRequest("重复执行检查"), "trace-duplicate");
        AgentToolExecutionAuditView duplicateAudit = auditService.listByRun(duplicateSession.sessionId(), duplicateRun.runId()).getFirst();
        service.executeToolExecution(duplicateSession.sessionId(), duplicateRun.runId(), duplicateAudit.auditId(), "trace-duplicate");

        PlatformBusinessException duplicateException = assertThrows(PlatformBusinessException.class,
                () -> service.executeToolExecution(duplicateSession.sessionId(), duplicateRun.runId(), duplicateAudit.auditId(), "trace-duplicate"));
        assertTrue(duplicateException.getMessage().contains("不处于 PLANNED"));

        AgentSessionView missingAdapterSession = service.createSession(new CreateAgentSessionRequest(
                10L,
                20L,
                null,
                "u-001",
                "web",
                "没有适配器的工具应失败",
                WorkspaceIsolationLevel.PROJECT,
                List.of(qualityTool())
        ));
        AgentRunView missingAdapterRun = service.startRun(missingAdapterSession.sessionId(), runRequest("执行质量建议工具"), "trace-missing");
        AgentToolExecutionAuditView missingAdapterAudit = auditService.listByRun(missingAdapterSession.sessionId(), missingAdapterRun.runId()).getFirst();

        AgentToolExecutionResultView missingAdapterResult = service.executeToolExecution(
                missingAdapterSession.sessionId(),
                missingAdapterRun.runId(),
                missingAdapterAudit.auditId(),
                "trace-missing"
        );

        assertEquals("FAILED", missingAdapterResult.audit().state());
        assertEquals("TOOL_ADAPTER_EXCEPTION", missingAdapterResult.audit().errorCode());
    }

    /**
     * 验证严格工具目录模式下不能绑定未注册工具。
     */
    @Test
    void createSessionShouldRejectUnregisteredToolWhenStrictModeEnabled() {
        CreateAgentSessionRequest request = new CreateAgentSessionRequest(
                10L,
                20L,
                null,
                "u-001",
                "web",
                "尝试绑定未注册工具",
                WorkspaceIsolationLevel.PROJECT,
                List.of(new BindAgentToolRequest(
                        "unknown.tool",
                        AgentToolType.KNOWLEDGE_RETRIEVAL,
                        "未知工具",
                        "unknown-service",
                        null,
                        true,
                        List.of("VIEW")
                ))
        );

        PlatformBusinessException exception = assertThrows(PlatformBusinessException.class,
                () -> service.createSession(request));
        assertTrue(exception.getMessage().contains("未注册"));
    }

    /**
     * 验证绑定时优先继承工具目录元数据，而不是信任请求体里可能被伪造的字段。
     */
    @Test
    void bindToolShouldInheritMetadataFromRegistry() {
        AgentSessionView session = service.createSession(new CreateAgentSessionRequest(
                10L,
                20L,
                null,
                "u-001",
                "web",
                "创建空工具会话",
                WorkspaceIsolationLevel.PROJECT,
                List.of()
        ));
        AgentSessionView updated = service.bindTool(session.sessionId(), new BindAgentToolRequest(
                "task.create",
                AgentToolType.DATASOURCE_METADATA,
                "伪造展示名称",
                "fake-service",
                null,
                true,
                List.of("VIEW")
        ));

        assertEquals("TASK_MANAGEMENT", updated.toolBindings().getFirst().toolType());
        assertEquals("task-management", updated.toolBindings().getFirst().targetService());
        assertEquals("HIGH", updated.toolBindings().getFirst().riskLevel());
        assertTrue(updated.toolBindings().getFirst().requiresApproval());
        assertEquals(List.of("CREATE"), updated.toolBindings().getFirst().allowedActions());
    }

    private CreateAgentSessionRequest baseSessionRequest() {
        return new CreateAgentSessionRequest(
                10L,
                20L,
                null,
                "u-001",
                "web",
                "围绕项目数据治理目标创建 Agent 会话",
                WorkspaceIsolationLevel.PROJECT,
                List.of(metadataTool())
        );
    }

    private CreateAgentSessionRequest highRiskSessionRequest(String objective) {
        return new CreateAgentSessionRequest(
                10L,
                20L,
                null,
                "u-001",
                "web",
                objective,
                WorkspaceIsolationLevel.PROJECT,
                List.of(metadataTool(), taskTool())
        );
    }

    private AgentToolExecutionAuditView firstWaitingApproval(String sessionId, String runId) {
        return auditService.listByRun(sessionId, runId).stream()
                .filter(item -> item.state().equals("WAITING_APPROVAL"))
                .findFirst()
                .orElseThrow();
    }

    private AgentRunView findRunView(String sessionId, String runId) {
        return service.getSession(sessionId).runs().stream()
                .filter(item -> item.runId().equals(runId))
                .findFirst()
                .orElseThrow();
    }

    private AgentToolAdapter metadataReadAdapterForTest() {
        return new AgentToolAdapter() {
            @Override
            public boolean supports(String toolCode) {
                return "datasource.metadata.read".equals(toolCode);
            }

            @Override
            public AgentToolExecutionOutcome execute(AgentToolExecutionContext context) {
                return AgentToolExecutionOutcome.succeeded(
                        "测试适配器模拟数据源元数据读取成功",
                        Map.of(
                                "datasourceId", context.audit().getTargetResourceId(),
                                "tableCount", 2
                        )
                );
            }
        };
    }

    private StartAgentRunRequest runRequest(String input) {
        return new StartAgentRunRequest(input, "AGENT_REASONING", false, Map.of("datasourceId", 1001L));
    }

    private BindAgentToolRequest metadataTool() {
        return new BindAgentToolRequest(
                "datasource.metadata.read",
                AgentToolType.DATASOURCE_METADATA,
                "数据源元数据读取",
                "datasource-management",
                1001L,
                true,
                List.of("VIEW")
        );
    }

    private BindAgentToolRequest qualityTool() {
        return new BindAgentToolRequest(
                "quality.rule.suggest",
                AgentToolType.DATA_QUALITY,
                "质量规则建议",
                "data-quality",
                null,
                true,
                List.of("VIEW", "GENERATE")
        );
    }

    private BindAgentToolRequest taskTool() {
        return new BindAgentToolRequest(
                "task.create",
                AgentToolType.TASK_MANAGEMENT,
                "任务创建",
                "task-management",
                null,
                false,
                List.of("CREATE")
        );
    }

    private AgentRuntimeProperties.ToolDefinitionProperties datasourceMetadataDefinition() {
        AgentRuntimeProperties.ToolDefinitionProperties tool = new AgentRuntimeProperties.ToolDefinitionProperties();
        tool.setToolCode("datasource.metadata.read");
        tool.setToolType(AgentToolType.DATASOURCE_METADATA);
        tool.setDisplayName("数据源元数据读取");
        tool.setTargetService("datasource-management");
        tool.setTargetEndpoint("/datasources/{datasourceId}/metadata");
        tool.setReadOnly(true);
        tool.setRiskLevel(AgentToolRiskLevel.LOW);
        tool.setExecutionMode(AgentToolExecutionMode.SYNC);
        tool.setAllowedActions(List.of("VIEW"));
        return tool;
    }

    private AgentRuntimeProperties.ToolDefinitionProperties qualityRuleDefinition() {
        AgentRuntimeProperties.ToolDefinitionProperties tool = new AgentRuntimeProperties.ToolDefinitionProperties();
        tool.setToolCode("quality.rule.suggest");
        tool.setToolType(AgentToolType.DATA_QUALITY);
        tool.setDisplayName("质量规则建议");
        tool.setTargetService("data-quality");
        tool.setReadOnly(true);
        tool.setRiskLevel(AgentToolRiskLevel.MEDIUM);
        tool.setExecutionMode(AgentToolExecutionMode.DRAFT_ONLY);
        tool.setAllowedActions(List.of("VIEW", "GENERATE"));
        return tool;
    }

    private AgentRuntimeProperties.ToolDefinitionProperties taskDefinition() {
        AgentRuntimeProperties.ToolDefinitionProperties tool = new AgentRuntimeProperties.ToolDefinitionProperties();
        tool.setToolCode("task.create");
        tool.setToolType(AgentToolType.TASK_MANAGEMENT);
        tool.setDisplayName("任务创建");
        tool.setTargetService("task-management");
        tool.setReadOnly(false);
        tool.setRiskLevel(AgentToolRiskLevel.HIGH);
        tool.setExecutionMode(AgentToolExecutionMode.APPROVAL_REQUIRED);
        tool.setRequiresApproval(true);
        tool.setIdempotent(false);
        tool.setAllowedActions(List.of("CREATE"));
        return tool;
    }
}
