/**
 * @Author : Cui
 * @Date: 2026/06/03 23:35
 * @Description DataSmart Govern Backend - AgentToolSandboxPolicyServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.tool.sandbox;

import com.czh.datasmart.govern.agent.config.AgentRuntimeProperties;
import com.czh.datasmart.govern.agent.model.AgentRunState;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionMode;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionState;
import com.czh.datasmart.govern.agent.model.AgentToolRiskLevel;
import com.czh.datasmart.govern.agent.model.AgentToolType;
import com.czh.datasmart.govern.agent.model.WorkspaceIsolationLevel;
import com.czh.datasmart.govern.agent.service.audit.AgentToolExecutionAuditRecord;
import com.czh.datasmart.govern.agent.service.session.AgentRunRecord;
import com.czh.datasmart.govern.agent.service.session.AgentSessionRecord;
import com.czh.datasmart.govern.agent.service.tool.AgentToolExecutionGuard;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent 工具调用沙箱策略测试。
 *
 * <p>这些测试保护的是“执行前安全治理”而不是某个具体工具适配器。
 * 真实产品中，模型可能规划出看似合理但带有风险的工具调用，例如 targetService 被篡改、参数过大、
 * 非幂等工具配置自动重试、敏感字段未审批等。沙箱策略必须在下游服务被调用前发现这些问题。</p>
 */
class AgentToolSandboxPolicyServiceTest {

    @Test
    void shouldAllowRegisteredReadOnlySyncTool() {
        AgentToolSandboxPolicyService service = new AgentToolSandboxPolicyService(baseProperties());

        AgentToolSandboxVerdict verdict = service.inspect(
                session(),
                run(),
                audit("datasource.metadata.read", "DATASOURCE_METADATA", "datasource-management",
                        "/datasources/{datasourceId}/metadata/discover", "LOW", "SYNC",
                        true, false, true, List.of("VIEW"), Map.of("datasourceId", 1001L), Map.of())
        );

        assertTrue(verdict.allowed());
        assertEquals("READ_ONLY_SYNC", verdict.isolationMode());
        assertTrue(verdict.issueCodes().isEmpty());
    }

    @Test
    void shouldBlockWhenTargetServiceDiffersFromToolRegistry() {
        AgentToolSandboxPolicyService service = new AgentToolSandboxPolicyService(baseProperties());

        AgentToolSandboxVerdict verdict = service.inspect(
                session(),
                run(),
                audit("datasource.metadata.read", "DATASOURCE_METADATA", "evil-service",
                        "/datasources/{datasourceId}/metadata/discover", "LOW", "SYNC",
                        true, false, true, List.of("VIEW"), Map.of("datasourceId", 1001L), Map.of())
        );

        assertFalse(verdict.allowed());
        assertTrue(verdict.issueCodes().contains("TARGET_SERVICE_MISMATCH"));
        assertTrue(verdict.issueCodes().contains("TARGET_SERVICE_BASE_URL_MISSING"));
    }

    @Test
    void shouldBlockOversizedArgumentsBeforeCallingDownstreamTool() {
        AgentRuntimeProperties properties = baseProperties();
        properties.getToolSandbox().setMaxArgumentBytes(32);
        AgentToolSandboxPolicyService service = new AgentToolSandboxPolicyService(properties);

        AgentToolSandboxVerdict verdict = service.inspect(
                session(),
                run(),
                audit("datasource.metadata.read", "DATASOURCE_METADATA", "datasource-management",
                        "/datasources/{datasourceId}/metadata/discover", "LOW", "SYNC",
                        true, false, true, List.of("VIEW"),
                        Map.of("datasourceId", 1001L, "payload", "x".repeat(256)), Map.of())
        );

        assertFalse(verdict.allowed());
        assertTrue(verdict.issueCodes().contains("ARGUMENT_BYTES_EXCEED_LIMIT"));
        assertTrue(verdict.argumentBytes() > verdict.maxArgumentBytes());
    }

    @Test
    void shouldBlockNonIdempotentToolWhenRetryIsConfigured() {
        AgentRuntimeProperties properties = baseProperties();
        properties.getToolRegistry().put("task.create.draft", taskDraftToolWithRetry());
        AgentToolSandboxPolicyService service = new AgentToolSandboxPolicyService(properties);
        AgentToolExecutionAuditRecord audit = audit("task.create.draft", "TASK_MANAGEMENT", "task-management",
                "/tasks", "HIGH", "APPROVAL_REQUIRED",
                false, true, false, List.of("CREATE"),
                Map.of("objective", "创建质量检查任务草稿"), Map.of());
        audit.approve("owner-001", "测试审批通过");

        AgentToolSandboxVerdict verdict = service.inspect(session(), run(), audit);

        assertFalse(verdict.allowed());
        assertTrue(verdict.issueCodes().contains("NON_IDEMPOTENT_RETRY_BLOCKED"));
    }

    @Test
    void shouldLetExecutionGuardFailClosedWhenSandboxRejectsTool() {
        AgentRuntimeProperties properties = baseProperties();
        properties.getToolSandbox().setMaxArgumentBytes(32);
        AgentToolExecutionGuard guard = new AgentToolExecutionGuard(new AgentToolSandboxPolicyService(properties));
        AgentToolExecutionAuditRecord audit = audit("datasource.metadata.read", "DATASOURCE_METADATA",
                "datasource-management", "/datasources/{datasourceId}/metadata/discover",
                "LOW", "SYNC", true, false, true, List.of("VIEW"),
                Map.of("datasourceId", 1001L, "payload", "x".repeat(256)), Map.of());

        PlatformBusinessException exception = assertThrows(PlatformBusinessException.class,
                () -> guard.validateBeforeExecution(session(), run(), audit));

        assertTrue(exception.getMessage().contains("工具调用沙箱策略拒绝执行"));
        assertTrue(exception.getMessage().contains("ARGUMENT_BYTES_EXCEED_LIMIT"));
    }

    private AgentRuntimeProperties baseProperties() {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.getToolServiceBaseUrls().put("datasource-management", "http://localhost:8082");
        properties.getToolServiceBaseUrls().put("task-management", "http://localhost:8081");
        properties.getToolRegistry().put("datasource.metadata.read", datasourceMetadataTool());
        return properties;
    }

    private AgentRuntimeProperties.ToolDefinitionProperties datasourceMetadataTool() {
        AgentRuntimeProperties.ToolDefinitionProperties tool = new AgentRuntimeProperties.ToolDefinitionProperties();
        tool.setEnabled(true);
        tool.setToolCode("datasource.metadata.read");
        tool.setToolType(AgentToolType.DATASOURCE_METADATA);
        tool.setTargetService("datasource-management");
        tool.setTargetEndpoint("/datasources/{datasourceId}/metadata/discover");
        tool.setReadOnly(true);
        tool.setRiskLevel(AgentToolRiskLevel.LOW);
        tool.setExecutionMode(AgentToolExecutionMode.SYNC);
        tool.setRequiresApproval(false);
        tool.setIdempotent(true);
        tool.setTimeoutMs(10000L);
        tool.setMaxRetries(1);
        tool.setAllowedActions(List.of("VIEW"));
        tool.setInputSchema(List.of(inputField("datasourceId", false)));
        return tool;
    }

    private AgentRuntimeProperties.ToolDefinitionProperties taskDraftToolWithRetry() {
        AgentRuntimeProperties.ToolDefinitionProperties tool = new AgentRuntimeProperties.ToolDefinitionProperties();
        tool.setEnabled(true);
        tool.setToolCode("task.create.draft");
        tool.setToolType(AgentToolType.TASK_MANAGEMENT);
        tool.setTargetService("task-management");
        tool.setTargetEndpoint("/tasks");
        tool.setReadOnly(false);
        tool.setRiskLevel(AgentToolRiskLevel.HIGH);
        tool.setExecutionMode(AgentToolExecutionMode.APPROVAL_REQUIRED);
        tool.setRequiresApproval(true);
        tool.setIdempotent(false);
        tool.setTimeoutMs(15000L);
        tool.setMaxRetries(1);
        tool.setAllowedActions(List.of("CREATE"));
        tool.setInputSchema(List.of(inputField("objective", false)));
        return tool;
    }

    private AgentRuntimeProperties.ToolInputFieldProperties inputField(String name, boolean sensitive) {
        AgentRuntimeProperties.ToolInputFieldProperties field = new AgentRuntimeProperties.ToolInputFieldProperties();
        field.setName(name);
        field.setType("string");
        field.setRequired(true);
        field.setSensitive(sensitive);
        return field;
    }

    private AgentSessionRecord session() {
        return new AgentSessionRecord(
                "session-001",
                10L,
                20L,
                null,
                "u-001",
                "WEB",
                "测试工具沙箱",
                WorkspaceIsolationLevel.PROJECT,
                "tenant:10:project:20",
                LocalDateTime.now()
        );
    }

    private AgentRunRecord run() {
        return new AgentRunRecord(
                "run-001",
                "session-001",
                AgentRunState.PLANNING,
                "AGENT_REASONING",
                "测试工具沙箱",
                true,
                false,
                List.of(),
                Map.of(),
                LocalDateTime.now(),
                "测试运行"
        );
    }

    private AgentToolExecutionAuditRecord audit(String toolCode,
                                                String toolType,
                                                String targetService,
                                                String targetEndpoint,
                                                String riskLevel,
                                                String executionMode,
                                                boolean readOnly,
                                                boolean requiresApproval,
                                                boolean idempotent,
                                                List<String> allowedActions,
                                                Map<String, Object> planArguments,
                                                Map<String, Object> parameterValidation) {
        return new AgentToolExecutionAuditRecord(
                "audit-001",
                "session-001",
                "run-001",
                "binding-001",
                toolCode,
                toolType,
                targetService,
                targetEndpoint,
                1001L,
                10L,
                20L,
                null,
                "u-001",
                riskLevel,
                executionMode,
                requiresApproval,
                readOnly,
                idempotent,
                allowedActions,
                "测试工具调用沙箱计划",
                planArguments,
                Map.of(),
                parameterValidation,
                AgentToolExecutionState.PLANNED,
                "trace-sandbox",
                "测试沙箱审计",
                LocalDateTime.now()
        );
    }
}
