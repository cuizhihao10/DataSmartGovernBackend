/**
 * @Author : Cui
 * @Date: 2026/05/24 13:41
 * @Description DataSmart Govern Backend - AgentToolExecutionGuardTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.tool;

import com.czh.datasmart.govern.agent.model.AgentRunState;
import com.czh.datasmart.govern.agent.model.AgentSessionState;
import com.czh.datasmart.govern.agent.model.AgentToolBindingStatus;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionState;
import com.czh.datasmart.govern.agent.model.AgentToolType;
import com.czh.datasmart.govern.agent.model.WorkspaceIsolationLevel;
import com.czh.datasmart.govern.agent.service.audit.AgentToolExecutionAuditRecord;
import com.czh.datasmart.govern.agent.service.session.AgentRunRecord;
import com.czh.datasmart.govern.agent.service.session.AgentDelegationRecord;
import com.czh.datasmart.govern.agent.service.session.AgentSessionRecord;
import com.czh.datasmart.govern.agent.service.session.AgentToolBindingRecord;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void shouldRejectExpiredOrRevokedDelegation() {
        LocalDateTime now = LocalDateTime.now();
        AgentDelegationRecord expired = new AgentDelegationRecord(
                "delegation-expired", "datasmart-govern-agent", "u-001", 10L, 20L,
                List.of("datasource.metadata.read"), List.of("VIEW"),
                List.of("datasource-management:1001"), AgentDelegationRecord.ACTIVE,
                now.minusHours(2), now.minusHours(1), null, now.minusHours(1)
        );
        PlatformBusinessException expiredException = assertThrows(PlatformBusinessException.class,
                () -> guard.validateBeforeExecution(sessionWithDelegation(expired), run(),
                        audit(true, null, Map.of())));
        assertEquals(PlatformErrorCode.FORBIDDEN, expiredException.getErrorCode());

        AgentSessionRecord revokedSession = session();
        revokedSession.getDelegation().revoke();
        PlatformBusinessException revokedException = assertThrows(PlatformBusinessException.class,
                () -> guard.validateBeforeExecution(revokedSession, run(), audit(true, null, Map.of())));
        assertEquals(PlatformErrorCode.FORBIDDEN, revokedException.getErrorCode());
    }

    @Test
    void shouldRejectResourceOutsideDelegationScope() {
        LocalDateTime now = LocalDateTime.now();
        AgentDelegationRecord resourceLimited = new AgentDelegationRecord(
                "delegation-resource-limited", "datasmart-govern-agent", "u-001", 10L, 20L,
                List.of("datasource.metadata.read"), List.of("VIEW"),
                List.of("datasource-management:2002"), AgentDelegationRecord.ACTIVE,
                now, null, null, now
        );

        PlatformBusinessException exception = assertThrows(PlatformBusinessException.class,
                () -> guard.validateBeforeExecution(sessionWithDelegation(resourceLimited), run(),
                        audit(true, null, Map.of())));

        assertEquals(PlatformErrorCode.FORBIDDEN, exception.getErrorCode());
    }

    private AgentSessionRecord sessionWithDelegation(AgentDelegationRecord delegation) {
        LocalDateTime now = LocalDateTime.now();
        return new AgentSessionRecord(
                "session-001", "datasmart-govern-agent", 10L, 20L, null, "u-001",
                null, null, null, "WEB", "委托边界测试", WorkspaceIsolationLevel.PROJECT,
                "tenant:10:project:20", AgentSessionState.ACTIVE, delegation, false,
                null, now, now, now, List.of(), List.of(), List.of()
        );
    }

    private AgentSessionRecord session() {
        AgentSessionRecord session = new AgentSessionRecord(
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
        session.addToolBinding(binding(
                "binding-metadata", "datasource.metadata.read", AgentToolType.DATASOURCE_METADATA,
                "datasource-management", "/metadata", true, List.of("VIEW")
        ));
        session.addToolBinding(binding(
                "binding-task", "task.create", AgentToolType.TASK_MANAGEMENT,
                "task-management", "/tasks", false, List.of("CREATE")
        ));
        return session;
    }

    private AgentToolBindingRecord binding(String bindingId,
                                           String toolCode,
                                           AgentToolType toolType,
                                           String targetService,
                                           String targetEndpoint,
                                           boolean readOnly,
                                           List<String> allowedActions) {
        return new AgentToolBindingRecord(
                bindingId,
                toolCode,
                toolType,
                toolCode,
                targetService,
                targetEndpoint,
                1001L,
                readOnly,
                readOnly ? "LOW" : "HIGH",
                readOnly ? "SYNC" : "APPROVAL_REQUIRED",
                !readOnly,
                readOnly,
                AgentToolBindingStatus.ENABLED,
                allowedActions,
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
