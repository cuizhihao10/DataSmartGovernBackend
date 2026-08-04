/**
 * @Author : Cui
 * @Date: 2026/05/24 00:00
 * @Description DataSmart Govern Backend - AgentPlanIngestionServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service;

import com.czh.datasmart.govern.agent.config.AgentRuntimeProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionDecisionRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionAuditView;
import com.czh.datasmart.govern.agent.controller.dto.IngestAgentPlanRequest;
import com.czh.datasmart.govern.agent.controller.dto.IngestAgentPlanToolRequest;
import com.czh.datasmart.govern.agent.controller.dto.IngestedAgentPlanView;
import com.czh.datasmart.govern.agent.event.NoopAgentToolExecutionEventPublisher;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionMode;
import com.czh.datasmart.govern.agent.model.AgentToolRiskLevel;
import com.czh.datasmart.govern.agent.model.AgentToolType;
import com.czh.datasmart.govern.agent.model.WorkspaceIsolationLevel;
import com.czh.datasmart.govern.agent.service.audit.AgentToolExecutionAuditMemoryStore;
import com.czh.datasmart.govern.agent.service.plan.AgentPlanIngestionIdempotencyStore;
import com.czh.datasmart.govern.agent.service.plan.AgentPlanIngestionIdempotencySupport;
import com.czh.datasmart.govern.agent.service.session.AgentSessionMemoryStore;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Python AgentPlan 接入服务单元测试。
 *
 * <p>这组测试专门保护“Python 计划进入 Java 控制面”的边界，而不复用 `AgentSessionServiceTest`。
 * 原因是 AgentPlan 接入有独立的商业化语义：
 * 1. Python Runtime 只能提交计划，不能直接执行工具；
 * 2. Java 工具目录必须拒绝未知工具；
 * 3. Run 和工具审计必须保留模型网关、记忆、参数校验等治理快照；
 * 4. 高风险工具必须让 Run 和 Audit 同时进入等待审批语义，不能出现状态不一致。
 */
class AgentPlanIngestionServiceTest {

    private AgentPlanIngestionService ingestionService;
    private AgentToolExecutionAuditService auditService;
    private AgentSessionMemoryStore sessionStore;

    @BeforeEach
    void setUp() {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.setMaxToolBindingsPerSession(5);
        properties.setMaxRunsPerSession(5);
        properties.setMaxActiveRunsPerSession(1);
        properties.setToolRegistry(new LinkedHashMap<>());
        properties.getToolRegistry().put("datasource.metadata.read", datasourceMetadataDefinition());
        properties.getToolRegistry().put("task.create", taskDefinition());
        AgentToolRegistryService toolRegistryService = new AgentToolRegistryService(properties);
        auditService = new AgentToolExecutionAuditService(
                new AgentToolExecutionAuditMemoryStore(),
                new NoopAgentToolExecutionEventPublisher()
        );
        sessionStore = new AgentSessionMemoryStore();
        ingestionService = new AgentPlanIngestionService(
                properties,
                sessionStore,
                toolRegistryService,
                auditService,
                new AgentPlanIngestionIdempotencySupport(new AgentPlanIngestionIdempotencyStore())
        );
    }

    /**
     * 验证低风险 Python AgentPlan 可以被转换为 Java 会话、Run 和工具审计。
     *
     * <p>这里重点不是测试模型推理，而是确认跨运行时契约：
     * Python 的模型网关治理摘要、记忆计划、参数校验结果都会进入 Java 可查询对象，
     * 后续审批、审计、前端调试和技术复盘不需要再去翻 Python 本地日志。
     */
    @Test
    void ingestPlanShouldCreateControlledRunAndPreserveGovernanceSnapshot() {
        IngestedAgentPlanView view = ingestionService.ingest(baseRequest(List.of(metadataPlan())), "trace-plan");

        assertNotNull(view.session().sessionId());
        assertEquals("ACTIVE", view.session().state());
        assertEquals("PLANNING", view.run().state());
        assertEquals("PYTHON_AI_RUNTIME_AGENT_PLAN", view.run().variables().get("source"));
        assertEquals("py-req-001", view.run().variables().get("pythonRequestId"));
        assertInstanceOf(Map.class, view.run().variables().get("modelGatewayGovernance"));
        assertEquals(1, view.toolAudits().size());
        AgentToolExecutionAuditView audit = view.toolAudits().getFirst();
        assertEquals("datasource.metadata.read", audit.toolCode());
        assertEquals("PLANNED", audit.state());
        assertEquals("读取数据源元数据，用于生成质量规则候选项", audit.planReason());
        assertEquals(1001L, audit.planArguments().get("datasourceId"));
        assertTrue(view.controlPlaneNotes().getFirst().contains("没有触发真实工具执行"));
    }

    /**
     * 验证高风险工具会让 Run 与工具审计同时进入审批等待状态。
     *
     * <p>这是本轮功能最关键的安全断言：
     * 如果 Run 是 WAITING_HUMAN，但工具审计仍是 PLANNED，后续执行入口可能误以为工具可以执行；
     * 因此 Java 接入层必须把 Python 计划风险和 Java 工具目录风险合并后写入审计。
     */
    @Test
    void ingestHighRiskPlanShouldWaitForHumanApproval() {
        IngestedAgentPlanView view = ingestionService.ingest(
                baseRequest(List.of(metadataPlan(), taskCreatePlan())),
                "trace-risk"
        );

        assertEquals("WAITING_HUMAN", view.run().state());
        assertTrue(view.run().requireHumanApproval());
        assertEquals(2, view.toolAudits().size());
        assertTrue(view.toolAudits().stream().anyMatch(item -> item.toolCode().equals("task.create")
                && item.state().equals("WAITING_APPROVAL")
                && Boolean.TRUE.equals(item.requiresApproval())
                && item.governanceHints().containsKey("approvalReason")));
    }

    /**
     * 验证未知工具会在进入 Run 和 Audit 前被拒绝。
     *
     * <p>这条规则体现“Java 控制面是工具能力白名单”的产品边界。
     * Python Runtime 即使模型规划出了一个看似合理的 toolCode，只要 Java 目录没有注册，就不能进入受控执行链路。
     */
    @Test
    void ingestPlanShouldRejectUnknownTool() {
        IngestAgentPlanToolRequest unknownTool = new IngestAgentPlanToolRequest(
                "unknown.tool",
                "模型误生成的未知工具",
                null,
                "LOW",
                "SYNC",
                false,
                Map.of(),
                Map.of(),
                Map.of()
        );

        PlatformBusinessException exception = assertThrows(PlatformBusinessException.class,
                () -> ingestionService.ingest(baseRequest(List.of(unknownTool)), "trace-unknown"));
        assertTrue(exception.getMessage().contains("未注册"));
    }

    @Test
    void ingestPlanShouldAcceptGovernedRuntimeDiscoveredMcpTool() {
        IngestAgentPlanToolRequest mcpTool = new IngestAgentPlanToolRequest(
                "mcp.enterprise.search",
                "检索企业事故知识库",
                null,
                "MEDIUM",
                "ASYNC_TASK",
                false,
                Map.of("query", "同步任务失败"),
                Map.of(
                        "protocolHint", "MCP",
                        "descriptorType", "MCP_REMOTE_TOOL",
                        "targetService", "python-ai-runtime-mcp-client",
                        "targetEndpoint", "mcp://untrusted-value-is-not-used",
                        "readOnly", true,
                        "idempotent", true
                ),
                Map.of("canExecute", true)
        );

        IngestedAgentPlanView view = ingestionService.ingest(baseRequest(List.of(mcpTool)), "trace-mcp");

        AgentToolExecutionAuditView audit = view.toolAudits().getFirst();
        assertEquals("mcp.enterprise.search", audit.toolCode());
        assertEquals("MCP_EXTERNAL_TOOL", audit.toolType());
        assertEquals("python-ai-runtime-mcp-client", audit.targetService());
        assertEquals(null, audit.targetEndpoint());
        assertEquals("ASYNC_TASK", audit.executionMode());
        assertEquals("WAITING_APPROVAL", audit.state());
    }

    @Test
    void ingestPlanShouldRejectMcpNameWithoutTrustedDescriptorHints() {
        IngestAgentPlanToolRequest spoofedMcpTool = new IngestAgentPlanToolRequest(
                "mcp.enterprise.search",
                "伪造 MCP 工具",
                null,
                "LOW",
                "SYNC",
                false,
                Map.of(),
                Map.of("targetService", "attacker-service"),
                Map.of()
        );

        PlatformBusinessException exception = assertThrows(PlatformBusinessException.class,
                () -> ingestionService.ingest(baseRequest(List.of(spoofedMcpTool)), "trace-mcp-spoof"));
        assertTrue(exception.getMessage().contains("未注册"));
    }

    /**
     * 验证同一个幂等键重复接入时会回放首次结果，而不是创建新的 Run 和审计计划。
     *
     * <p>这条能力现在服务 HTTP 重试，下一阶段会直接服务 Kafka 异步消费：
     * Kafka Consumer 可能因为 ack 丢失、rebalance 或进程重启再次收到同一条 AgentPlan 消息。
     * 如果没有幂等保护，重复消息会生成多份审批单，严重时会导致同一个治理动作被执行多次。
     */
    @Test
    void ingestPlanShouldReplayResultWhenIdempotencyKeyRepeated() {
        IngestAgentPlanRequest request = baseRequest(List.of(metadataPlan()));

        IngestedAgentPlanView first = ingestionService.ingest(request, "trace-first");
        IngestedAgentPlanView replay = ingestionService.ingest(request, "trace-retry");

        assertEquals(first.run().runId(), replay.run().runId());
        assertEquals(first.session().sessionId(), replay.session().sessionId());
        assertEquals(first.toolAudits().getFirst().auditId(), replay.toolAudits().getFirst().auditId());
    }

    /**
     * 验证同一个幂等键不能被不同请求复用。
     *
     * <p>幂等键的业务含义是“这就是同一次计划接入”。
     * 如果调用方用同一个 key 发送不同 objective、不同工具计划或不同项目边界，系统必须拒绝，
     * 否则后续审计人员看到的 runId/auditId 可能与真实请求内容对不上。
     */
    @Test
    void ingestPlanShouldRejectSameIdempotencyKeyWithDifferentPayload() {
        IngestAgentPlanRequest first = baseRequest(List.of(metadataPlan()));
        IngestAgentPlanRequest changed = new IngestAgentPlanRequest(
                first.sessionId(),
                first.tenantId(),
                first.projectId(),
                first.workspaceId(),
                first.actorId(),
                first.channel(),
                first.objective(),
                "这是同一个幂等键下的不同用户输入，应该被拒绝。",
                first.workloadType(),
                first.idempotencyKey(),
                first.pythonRequestId(),
                first.stateTrace(),
                first.responseSummary(),
                first.requiresHumanApproval(),
                first.isolationLevel(),
                first.toolPlans(),
                first.modelGatewayGovernance(),
                first.memoryPlan(),
                first.memoryRetrievalReport()
        );

        ingestionService.ingest(first, "trace-first");
        PlatformBusinessException exception = assertThrows(PlatformBusinessException.class,
                () -> ingestionService.ingest(changed, "trace-conflict"));
        assertTrue(exception.getMessage().contains("幂等键"));
    }

    @Test
    void ingestPlanShouldPreserveNullOptionalJsonFieldsWithoutCrashing() {
        Map<String, Object> governanceHints = new LinkedHashMap<>();
        governanceHints.put("modelToolCallId", "call-null-safe");
        governanceHints.put("optionalResultAlias", null);
        IngestAgentPlanToolRequest plan = new IngestAgentPlanToolRequest(
                "datasource.metadata.read",
                "Read datasource metadata with an unresolved optional result alias.",
                1001L,
                "LOW",
                "SYNC",
                false,
                Map.of("datasourceId", 1001L),
                governanceHints,
                Map.of("canExecute", true)
        );

        IngestedAgentPlanView view = ingestionService.ingest(baseRequest(List.of(plan)), "trace-null-safe");

        assertTrue(view.toolAudits().getFirst().governanceHints().containsKey("optionalResultAlias"));
        assertEquals(null, view.toolAudits().getFirst().governanceHints().get("optionalResultAlias"));
    }

    /**
     * 用户补齐数据源、对象映射和字段映射后，应能在同一会话用新计划替代上一轮尚未执行的 PLANNING Run。
     *
     * <p>该回归场景对应真实页面故障：旧 Run 占用唯一 active slot 后，第二轮配置会被 409 拒绝。测试同时确认
     * 旧 Run 与旧工具审计都进入 CANCELLED，并且新 Run 的工具参数保留补参后的映射，防止只放开并发限制却
     * 丢失用户刚填写的配置。</p>
     */
    @Test
    void ingestFollowUpShouldSupersedeUnexecutedPlanningRunAndPreserveMappings() {
        IngestedAgentPlanView first = ingestionService.ingest(baseRequest(List.of(metadataPlan())), "trace-initial");
        IngestAgentPlanToolRequest mappedPlan = metadataPlanWithMappings();

        IngestedAgentPlanView followUp = ingestionService.ingest(
                followUpRequest(first.session().sessionId(), "idem-plan-follow-up", List.of(mappedPlan)),
                "trace-follow-up"
        );

        assertEquals("CANCELLED", sessionStore.findById(first.session().sessionId()).orElseThrow()
                .getRuns().getFirst().getState().name());
        assertEquals("CANCELLED", auditService.listByRun(first.session().sessionId(), first.run().runId())
                .getFirst().state());
        assertEquals("PLANNING", followUp.run().state());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> compactPlans = (List<Map<String, Object>>) followUp.run().variables().get("toolPlans");
        @SuppressWarnings("unchecked")
        Map<String, Object> arguments = (Map<String, Object>) compactPlans.getFirst().get("arguments");
        assertEquals(2, ((List<?>) arguments.get("objectMappings")).size());
    }

    /**
     * WAITING_HUMAN 但尚未审批/执行的旧计划同样可以被用户纠偏后的新计划替代。
     *
     * <p>旧 WAITING_APPROVAL 审计必须同步取消，否则审批中心仍会展示一张基于旧参数的可操作审批单。</p>
     */
    @Test
    void ingestFollowUpShouldCancelWaitingApprovalAuditBeforeCreatingNewRun() {
        IngestedAgentPlanView first = ingestionService.ingest(baseRequest(List.of(taskCreatePlan())), "trace-waiting");

        IngestedAgentPlanView followUp = ingestionService.ingest(
                followUpRequest(first.session().sessionId(), "idem-plan-after-confirmation", List.of(metadataPlan())),
                "trace-after-confirmation"
        );

        assertEquals("CANCELLED", auditService.listByRun(first.session().sessionId(), first.run().runId())
                .getFirst().state());
        assertEquals("PLANNING", followUp.run().state());
    }

    /**
     * 连接测试和元数据读取已经成功时，用户仍应能补齐映射并继续同一会话。
     *
     * <p>真实创建向导会先执行只读数据源核对，再等待用户填写对象映射和字段映射。成功的只读审计必须保留为
     * SUCCEEDED，尚未执行的写工具必须取消；如果把所有结果态一律视为冲突，页面仍会复现“已经补充映射却只能
     * 看到弹窗”的问题。</p>
     */
    @Test
    void ingestFollowUpShouldPreserveSucceededReadOnlyAuditAndCancelPendingWritePlan() {
        IngestedAgentPlanView first = ingestionService.ingest(
                baseRequest(List.of(metadataPlan(), taskCreatePlan())),
                "trace-read-completed"
        );
        AgentToolExecutionAuditView metadataAudit = first.toolAudits().stream()
                .filter(item -> item.toolCode().equals("datasource.metadata.read"))
                .findFirst()
                .orElseThrow();
        var executingMetadata = auditService.startExecution(
                first.session().sessionId(), first.run().runId(), metadataAudit.auditId());
        auditService.succeedExecution(executingMetadata, "元数据读取完成", "已读取低敏表结构摘要");

        IngestedAgentPlanView followUp = ingestionService.ingest(
                followUpRequest(first.session().sessionId(), "idem-after-read-only-result", List.of(metadataPlanWithMappings())),
                "trace-after-read-only-result"
        );

        List<AgentToolExecutionAuditView> oldAudits = auditService.listByRun(
                first.session().sessionId(), first.run().runId());
        assertEquals("SUCCEEDED", oldAudits.stream()
                .filter(item -> item.toolCode().equals("datasource.metadata.read"))
                .findFirst().orElseThrow().state());
        assertEquals("CANCELLED", oldAudits.stream()
                .filter(item -> item.toolCode().equals("task.create"))
                .findFirst().orElseThrow().state());
        assertEquals("CANCELLED", sessionStore.findById(first.session().sessionId()).orElseThrow()
                .getRuns().getFirst().getState().name());
        assertEquals("PLANNING", followUp.run().state());
    }

    /**
     * 一旦旧工具进入 EXECUTING，新自然语言回合不得覆盖真实执行证据。
     *
     * <p>这条负向断言保护安全边界：自动替代只用于未执行补参，不是“取消任意正在运行任务”的后门。</p>
     */
    @Test
    void ingestFollowUpShouldRejectWhenPreviousToolAlreadyStarted() {
        IngestedAgentPlanView first = ingestionService.ingest(baseRequest(List.of(metadataPlan())), "trace-running");
        auditService.startExecution(
                first.session().sessionId(),
                first.run().runId(),
                first.toolAudits().getFirst().auditId()
        );

        PlatformBusinessException exception = assertThrows(
                PlatformBusinessException.class,
                () -> ingestionService.ingest(
                        followUpRequest(first.session().sessionId(), "idem-plan-running-conflict", List.of(metadataPlan())),
                        "trace-running-conflict"
                )
        );

        assertTrue(exception.getMessage().contains("不能用补参计划覆盖"));
        assertEquals("EXECUTING", auditService.listByRun(first.session().sessionId(), first.run().runId())
                .getFirst().state());
    }

    /**
     * 已成功的非只读工具必须阻止自动替代，避免掩盖已经发生的创建或写入副作用。
     */
    @Test
    void ingestFollowUpShouldRejectWhenPreviousWriteToolSucceeded() {
        IngestedAgentPlanView first = ingestionService.ingest(baseRequest(List.of(taskCreatePlan())), "trace-write-completed");
        AgentToolExecutionAuditView taskAudit = first.toolAudits().getFirst();
        auditService.approve(
                first.session().sessionId(),
                first.run().runId(),
                taskAudit.auditId(),
                new AgentToolExecutionDecisionRequest("1001", "测试已批准写工具")
        );
        var executingTask = auditService.startExecution(
                first.session().sessionId(), first.run().runId(), taskAudit.auditId());
        auditService.succeedExecution(executingTask, "任务已创建", "taskId=2001");

        PlatformBusinessException exception = assertThrows(
                PlatformBusinessException.class,
                () -> ingestionService.ingest(
                        followUpRequest(first.session().sessionId(), "idem-after-write-result", List.of(metadataPlan())),
                        "trace-after-write-result"
                )
        );

        assertTrue(exception.getMessage().contains("不能用补参计划覆盖"));
        assertTrue(exception.getMessage().contains("readOnly=false"));
        assertEquals("SUCCEEDED", auditService.listByRun(first.session().sessionId(), first.run().runId())
                .getFirst().state());
    }

    private IngestAgentPlanRequest baseRequest(List<IngestAgentPlanToolRequest> toolPlans) {
        return new IngestAgentPlanRequest(
                null,
                10L,
                20L,
                null,
                "u-001",
                "python",
                "分析数据源并生成可治理的执行计划",
                "请分析数据源质量问题，并判断是否需要创建治理任务",
                "AGENT_REASONING",
                "idem-plan-001",
                "py-req-001",
                List.of("MODEL_GATEWAY_ROUTED", "TOOL_PLANNED"),
                "已生成工具计划，等待 Java 控制面接入。",
                false,
                WorkspaceIsolationLevel.PROJECT,
                toolPlans,
                Map.of("selectedProvider", "local-vllm", "cacheKeyScope", "PROJECT_SAFE"),
                Map.of("writePolicy", "EPISODIC"),
                Map.of("retrievedCount", 3)
        );
    }

    /** 构造同一会话的新自然语言回合，并使用独立幂等键模拟真实前端补参请求。 */
    private IngestAgentPlanRequest followUpRequest(String sessionId,
                                                   String idempotencyKey,
                                                   List<IngestAgentPlanToolRequest> toolPlans) {
        IngestAgentPlanRequest base = baseRequest(toolPlans);
        return new IngestAgentPlanRequest(
                sessionId,
                base.tenantId(),
                base.projectId(),
                base.workspaceId(),
                base.actorId(),
                base.channel(),
                base.objective(),
                "已补齐目标数据源、两条对象映射和十个同名字段映射，请继续核对。",
                base.workloadType(),
                idempotencyKey,
                "py-req-" + idempotencyKey,
                base.stateTrace(),
                base.responseSummary(),
                base.requiresHumanApproval(),
                base.isolationLevel(),
                toolPlans,
                base.modelGatewayGovernance(),
                base.memoryPlan(),
                base.memoryRetrievalReport()
        );
    }

    /** 构造携带两条真实对象映射的计划，用于验证补参配置不会在 Java 接入边界丢失。 */
    private IngestAgentPlanToolRequest metadataPlanWithMappings() {
        return new IngestAgentPlanToolRequest(
                "datasource.metadata.read",
                "读取用户补参后的数据源与映射元数据",
                1001L,
                "LOW",
                "SYNC",
                false,
                Map.of(
                        "datasourceId", 1001L,
                        "objectMappings", List.of(
                                Map.of("sourceObjectName", "fs_test_customer_source",
                                        "targetSchemaName", "public",
                                        "targetObjectName", "fs_test_customer_source"),
                                Map.of("sourceObjectName", "fs_test_customer_target",
                                        "targetSchemaName", "public",
                                        "targetObjectName", "fs_test_customer_target")
                        )
                ),
                Map.of("projectScoped", true),
                Map.of("missingFields", List.of())
        );
    }

    private IngestAgentPlanToolRequest metadataPlan() {
        return new IngestAgentPlanToolRequest(
                "datasource.metadata.read",
                "读取数据源元数据，用于生成质量规则候选项",
                1001L,
                "LOW",
                "SYNC",
                false,
                Map.of("datasourceId", 1001L),
                Map.of("projectScoped", true, "cachePolicy", "PROJECT_SAFE"),
                Map.of("missingFields", List.of(), "filledFromContext", List.of("tenantId", "projectId"))
        );
    }

    private IngestAgentPlanToolRequest taskCreatePlan() {
        return new IngestAgentPlanToolRequest(
                "task.create",
                "创建治理任务会影响项目任务队列，因此必须先进入人工确认",
                null,
                "HIGH",
                "APPROVAL_REQUIRED",
                true,
                Map.of("taskType", "DATA_QUALITY_REMEDIATION"),
                Map.of("approvalReason", "CREATE_TASK_FROM_AGENT_PLAN"),
                Map.of("missingFields", List.of("ownerId"))
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

    private AgentRuntimeProperties.ToolDefinitionProperties taskDefinition() {
        AgentRuntimeProperties.ToolDefinitionProperties tool = new AgentRuntimeProperties.ToolDefinitionProperties();
        tool.setToolCode("task.create");
        tool.setToolType(AgentToolType.TASK_MANAGEMENT);
        tool.setDisplayName("任务创建");
        tool.setTargetService("task-management");
        tool.setTargetEndpoint("/tasks");
        tool.setReadOnly(false);
        tool.setRiskLevel(AgentToolRiskLevel.HIGH);
        tool.setExecutionMode(AgentToolExecutionMode.APPROVAL_REQUIRED);
        tool.setRequiresApproval(true);
        tool.setIdempotent(false);
        tool.setAllowedActions(List.of("CREATE"));
        return tool;
    }
}
