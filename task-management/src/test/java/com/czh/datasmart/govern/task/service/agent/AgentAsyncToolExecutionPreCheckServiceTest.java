/**
 * @Author : Cui
 * @Date: 2026/06/01 21:18
 * @Description DataSmart Govern Backend - AgentAsyncToolExecutionPreCheckServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent 异步工具执行前二次复核测试。
 *
 * <p>这些用例不调用真实 data-sync，也不调用 agent-runtime 远端接口。
 * 目标是把 worker 本地已经能判断的安全门固定下来：任务状态、Agent 审计状态、工具白名单和执行证据。
 * 远端 confirmation 反查与 permission-admin 策略实时 evaluate 会在后续批次继续扩展。</p>
 */
class AgentAsyncToolExecutionPreCheckServiceTest {

    @Test
    void shouldAllowExecutablePayloadWithSelectedNodeEvidence() {
        AgentAsyncToolExecutionPreCheckService service = new AgentAsyncToolExecutionPreCheckService(List.of(new FakeExecutor()));

        AgentAsyncToolExecutionPreCheckResult result = service.preCheck(payload("RUNNING", "PLANNED",
                "dag-confirmation:test-001", List.of("route-policy:860")));

        assertTrue(result.allowed());
        assertTrue(result.validationMessages().stream().anyMatch(message -> message.contains("工具白名单复核通过")));
    }

    @Test
    void shouldRejectPayloadWithoutWhitelistExecutor() {
        AgentAsyncToolExecutionPreCheckService service = new AgentAsyncToolExecutionPreCheckService(List.of());

        AgentAsyncToolExecutionPreCheckResult result = service.preCheck(payload("RUNNING", "PLANNED",
                "dag-confirmation:test-001", List.of("route-policy:860")));

        assertFalse(result.allowed());
        assertTrue(result.message().contains("白名单适配器"));
    }

    @Test
    void shouldRejectWaitingApprovalAuditStateBeforeSideEffect() {
        AgentAsyncToolExecutionPreCheckService service = new AgentAsyncToolExecutionPreCheckService(List.of(new FakeExecutor()));

        AgentAsyncToolExecutionPreCheckResult result = service.preCheck(payload("RUNNING", "WAITING_APPROVAL",
                "dag-confirmation:test-001", List.of("route-policy:860")));

        assertFalse(result.allowed());
        assertTrue(result.message().contains("审计状态不允许"));
    }

    @Test
    void shouldRejectSensitiveDelegationEvidence() {
        AgentAsyncToolExecutionPreCheckService service = new AgentAsyncToolExecutionPreCheckService(List.of(new FakeExecutor()));

        AgentAsyncToolExecutionPreCheckResult result = service.preCheck(payload("RUNNING", "PLANNED",
                "dag-confirmation:test-001", List.of("route-policy:860"),
                List.of("prompt: system secret")));

        assertFalse(result.allowed());
        assertTrue(result.message().contains("低敏审计摘要"));
    }

    private AgentAsyncToolResolvedPayload payload(String taskStatus,
                                                  String auditState,
                                                  String confirmationId,
                                                  List<String> policyVersions) {
        return payload(taskStatus, auditState, confirmationId, policyVersions,
                List.of("serviceAccount=datasmart-agent-runtime;representedActor=actor-agent"));
    }

    private AgentAsyncToolResolvedPayload payload(String taskStatus,
                                                  String auditState,
                                                  String confirmationId,
                                                  List<String> policyVersions,
                                                  List<String> delegationEvidence) {
        return new AgentAsyncToolResolvedPayload(
                9001L,
                taskStatus,
                AgentAsyncToolPayloadResolver.TASK_TYPE,
                "cmd-001",
                "agent-tool-audit://session-001/run-001/audit-001/plan-arguments",
                "plan-arguments",
                "session-001",
                "run-001",
                "audit-001",
                "data-sync.execute",
                "data-sync",
                "/internal/data-sync/agent/tasks/execute",
                10L,
                20L,
                30L,
                "1001",
                "trace-worker",
                "ASYNC_TASK",
                auditState,
                List.of("syncTemplateId"),
                List.of(),
                24,
                false,
                confirmationId,
                policyVersions,
                delegationEvidence,
                Map.of("syncTemplateId", 6001L),
                Map.of(),
                Map.of(),
                List.of("预检通过"),
                List.of(),
                LocalDateTime.now()
        );
    }

    private static class FakeExecutor implements AgentAsyncToolExecutor {

        @Override
        public boolean supports(String toolCode) {
            return "data-sync.execute".equals(toolCode);
        }

        @Override
        public AgentAsyncToolExecutionResult execute(AgentAsyncToolResolvedPayload payload) {
            return AgentAsyncToolExecutionResult.success("不会在 pre-check 测试中真正执行", Map.of());
        }
    }
}
