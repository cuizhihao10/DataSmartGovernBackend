/**
 * @Author : Cui
 * @Date: 2026/06/01 22:26
 * @Description DataSmart Govern Backend - AgentAsyncToolGuardrailEventSupportTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent worker guardrail 事件分类测试。
 *
 * <p>这些测试保护“面向 runtime event display 的稳定错误码”。
 * 如果后续所有执行前阻断都退化成一个泛化错误码，前端、审计台和运维指标就很难区分权限拒绝、
 * 策略漂移、确认不一致或控制面不可用，最终只能靠人工读中文 message 排障。</p>
 */
class AgentAsyncToolGuardrailEventSupportTest {

    @Test
    void shouldClassifyPermissionDeniedPreCheck() {
        AgentAsyncToolGuardrailEventSupport support = new AgentAsyncToolGuardrailEventSupport();
        AgentAsyncToolExecutionPreCheckResult result = AgentAsyncToolExecutionPreCheckResult.rejected(
                "AGENT_ASYNC_TOOL_PRECHECK_REJECTED",
                "permission-admin 拒绝 Agent worker 执行已确认异步工具",
                List.of("permission-admin 实时授权复核阻断"),
                Map.of("permissionAllowed", false)
        );

        Map<String, Object> output = support.preCheckOutput(result);

        assertEquals("AGENT_ASYNC_TOOL_PERMISSION_DENIED", support.preCheckErrorCode(result));
        assertEquals("AGENT_ASYNC_TOOL_PERMISSION_DENIED", output.get("guardrailReasonCode"));
        assertEquals("BLOCKED_BEFORE_SIDE_EFFECT", output.get("guardrailDecision"));
        assertTrue((Boolean) output.get("guardrail"));
    }

    @Test
    void shouldClassifyPermissionUnavailableAsDeferredGuardrail() {
        AgentAsyncToolGuardrailEventSupport support = new AgentAsyncToolGuardrailEventSupport();
        AgentAsyncToolPreCheckUnavailableException exception = new AgentAsyncToolPreCheckUnavailableException(
                "permission-admin 授权复核暂时不可用，worker 已阻止副作用并等待重试。",
                new IllegalStateException("timeout")
        );

        Map<String, Object> output = support.unavailableOutput(exception);

        assertEquals("AGENT_ASYNC_TOOL_PERMISSION_UNAVAILABLE", support.unavailableErrorCode(exception));
        assertEquals("AGENT_ASYNC_TOOL_PERMISSION_UNAVAILABLE", output.get("guardrailReasonCode"));
        assertEquals("DEFERRED_WAITING_RETRY", output.get("guardrailDecision"));
    }
}
