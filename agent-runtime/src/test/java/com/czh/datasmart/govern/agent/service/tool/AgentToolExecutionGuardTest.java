/**
 * @Author : Cui
 * @Date: 2026/05/24 13:41
 * @Description DataSmart Govern Backend - AgentToolExecutionGuardTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.tool;

import com.czh.datasmart.govern.agent.model.AgentRunState;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionState;
import com.czh.datasmart.govern.agent.model.WorkspaceIsolationLevel;
import com.czh.datasmart.govern.agent.service.audit.AgentToolExecutionAuditRecord;
import com.czh.datasmart.govern.agent.service.session.AgentRunRecord;
import com.czh.datasmart.govern.agent.service.session.AgentSessionRecord;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent 工具执行守卫测试。
 *
 * <p>这组测试只验证执行前安全规则，不启动 Spring 容器、不调用真实工具适配器。
 * 它保护的是商业化 Agent 工具执行最关键的“最后一道门”：
 * 即使 AgentPlan 已经进入 Java 控制面，真正执行前仍要重新检查参数完整性和审批事实。
 */
class AgentToolExecutionGuardTest {

    private final AgentToolExecutionGuard guard = new AgentToolExecutionGuard();

    @Test
    void shouldRejectWhenParameterValidationStillHasMissingFields() {
        PlatformBusinessException exception = assertThrows(PlatformBusinessException.class,
                () -> guard.validateBeforeExecution(session(), run(), audit(true, null,
                        Map.of("missingFields", List.of("datasourceId")))));

        assertTrue(exception.getMessage().contains("缺失字段"));
    }

    @Test
    void shouldRejectWriteToolWithoutApprovalOperator() {
        PlatformBusinessException exception = assertThrows(PlatformBusinessException.class,
                () -> guard.validateBeforeExecution(session(), run(), audit(false, null, Map.of())));

        assertTrue(exception.getMessage().contains("非只读工具"));
    }

    @Test
    void shouldAllowApprovedWriteTool() {
        AgentToolExecutionAuditRecord audit = audit(false, "owner-001", Map.of());

        guard.validateBeforeExecution(session(), run(), audit);
    }

    private AgentSessionRecord session() {
        return new AgentSessionRecord(
                "session-001",
                10L,
                20L,
                null,
                "u-001",
                "WEB",
                "测试工具执行守卫",
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
                "测试执行",
                true,
                false,
                List.of(),
                Map.of(),
                LocalDateTime.now(),
                "测试运行"
        );
    }

    private AgentToolExecutionAuditRecord audit(boolean readOnly,
                                                String approvalOperatorId,
                                                Map<String, Object> parameterValidation) {
        AgentToolExecutionAuditRecord record = new AgentToolExecutionAuditRecord(
                "audit-001",
                "session-001",
                "run-001",
                "binding-001",
                readOnly ? "datasource.metadata.read" : "task.create",
                readOnly ? "DATASOURCE_METADATA" : "TASK_MANAGEMENT",
                readOnly ? "datasource-management" : "task-management",
                readOnly ? "/metadata" : "/tasks",
                1001L,
                10L,
                20L,
                null,
                "u-001",
                readOnly ? "LOW" : "HIGH",
                readOnly ? "SYNC" : "APPROVAL_REQUIRED",
                !readOnly,
                readOnly,
                true,
                readOnly ? List.of("VIEW") : List.of("CREATE"),
                "测试工具计划",
                Map.of("datasourceId", 1001L),
                Map.of(),
                parameterValidation,
                AgentToolExecutionState.PLANNED,
                "trace-guard",
                "测试审计",
                LocalDateTime.now()
        );
        if (approvalOperatorId != null) {
            record.approve(approvalOperatorId, "测试审批通过");
        }
        return record;
    }
}
