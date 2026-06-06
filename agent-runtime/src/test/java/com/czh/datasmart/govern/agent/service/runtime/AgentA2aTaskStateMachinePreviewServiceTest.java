/**
 * @Author : Cui
 * @Date: 2026/06/06 12:40
 * @Description DataSmart Govern Backend - AgentA2aTaskStateMachinePreviewServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentA2aTaskStateMachinePreviewResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentA2aTaskStateView;
import com.czh.datasmart.govern.agent.controller.dto.AgentA2aTaskTransitionView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A2A Task 状态机只读预览测试。
 *
 * <p>这组测试不是为了验证某个真实任务能否执行，而是保护“协议生命周期合同”。如果未来有人在没有讨论产品语义的情况下
 * 往状态机里加一条 `completed -> working`，或者把终态任务重新拉回 worker，就会直接破坏 A2A 对外兼容和
 * DataSmart 的审计语义。因此测试重点放在标准状态、终态约束、流转守卫和敏感信息不扩散。</p>
 */
class AgentA2aTaskStateMachinePreviewServiceTest {

    private AgentA2aTaskStateMachinePreviewService service;

    @BeforeEach
    void setUp() {
        service = new AgentA2aTaskStateMachinePreviewService();
    }

    /**
     * 验证状态机包含 A2A task 生命周期的标准状态。
     *
     * <p>这里把 `TASK_STATE_UNSPECIFIED` 也纳入检查，但它只应作为诊断态存在，不能参与正常业务流转。
     * 这让 DataSmart 既能兼容 A2A 最新规范中的兜底表达，又不会把“未知状态”误当成可执行任务状态。</p>
     */
    @Test
    void buildPreviewShouldExposeA2aStandardTaskStates() {
        AgentA2aTaskStateMachinePreviewResponse response = service.buildPreview();
        Set<String> states = response.states()
                .stream()
                .map(AgentA2aTaskStateView::state)
                .collect(Collectors.toSet());

        assertTrue(response.previewOnly());
        assertFalse(response.taskEndpointEnabled());
        assertEquals("A2A", response.protocolFamily());
        assertTrue(states.containsAll(Set.of(
                "TASK_STATE_UNSPECIFIED",
                "TASK_STATE_SUBMITTED",
                "TASK_STATE_WORKING",
                "TASK_STATE_INPUT_REQUIRED",
                "TASK_STATE_AUTH_REQUIRED",
                "TASK_STATE_COMPLETED",
                "TASK_STATE_FAILED",
                "TASK_STATE_CANCELED",
                "TASK_STATE_REJECTED"
        )));
        assertTrue(response.states().stream()
                .filter(state -> state.state().equals("TASK_STATE_AUTH_REQUIRED"))
                .findFirst()
                .orElseThrow()
                .interrupted());
    }

    /**
     * 验证终态不会再作为流转起点。
     *
     * <p>A2A 任务一旦进入 completed、failed、canceled、rejected，就不能被“恢复到 working”。如果业务上需要重跑，
     * 应创建新 task 或管理员补偿记录。这样可以保证外部 Agent、前端 timeline、审计台和 worker receipt
     * 对终态的理解一致。</p>
     */
    @Test
    void terminalStatesShouldNotHaveOutboundTransitions() {
        AgentA2aTaskStateMachinePreviewResponse response = service.buildPreview();
        Set<String> terminalStates = response.states()
                .stream()
                .filter(AgentA2aTaskStateView::terminal)
                .map(AgentA2aTaskStateView::state)
                .collect(Collectors.toSet());
        Set<String> transitionSources = response.transitions()
                .stream()
                .map(AgentA2aTaskTransitionView::fromState)
                .collect(Collectors.toSet());

        for (String terminalState : terminalStates) {
            assertFalse(transitionSources.contains(terminalState), "终态不应再有出边：" + terminalState);
        }
    }

    /**
     * 验证所有流转都映射在已声明的 A2A 状态集合内，并且每条流转都有治理守卫和回放策略。
     *
     * <p>真实商业化 task endpoint 的风险往往不在状态名本身，而在“什么时候可以进入下一个状态”。因此每条流转
     * 都必须说明权限、审批、幂等、worker、副作用或超时边界；否则后续实现很容易出现绕过 permission-admin
     * 或重复执行工具的漏洞。</p>
     */
    @Test
    void transitionsShouldStayWithinDeclaredStatesAndCarryGuardrails() {
        AgentA2aTaskStateMachinePreviewResponse response = service.buildPreview();
        Set<String> states = response.states()
                .stream()
                .map(AgentA2aTaskStateView::state)
                .collect(Collectors.toSet());

        for (AgentA2aTaskTransitionView transition : response.transitions()) {
            assertTrue(states.contains(transition.fromState()));
            assertTrue(states.contains(transition.toState()));
            assertTrue(transition.allowed());
            assertFalse(transition.guardrail().isBlank());
            assertFalse(transition.replayPolicy().isBlank());
            assertFalse(transition.eventPolicy().isBlank());
            assertFalse(transition.datasmartInternalPhases().isEmpty());
        }
    }

    /**
     * 验证预览响应不会把敏感 payload 字段或内部执行细节扩散出去。
     *
     * <p>状态机可以解释“哪些内容不能保存”，但响应本身不应该出现容易被前端或外部 Agent 当成字段契约的敏感英文键。
     * 这条测试是低敏边界的护栏：后续如果有人为了调试把内部 endpoint、工具参数字段名、密钥或查询语句示例塞进 preview，
     * 测试会直接失败。</p>
     */
    @Test
    void previewShouldNotExposeSensitivePayloadKeys() throws Exception {
        AgentA2aTaskStateMachinePreviewResponse response = service.buildPreview();
        String serialized = objectMapper().writeValueAsString(response).toLowerCase(Locale.ROOT);
        List<String> forbiddenTokens = List.of(
                "toolarguments",
                "targetendpoint",
                "api-key",
                "secret",
                "\"prompt\"",
                "modeloutput",
                "sample data",
                "drop table",
                "internal.example.com"
        );

        for (String token : forbiddenTokens) {
            assertFalse(serialized.contains(token), "预览响应不应包含敏感 token：" + token);
        }
    }

    private ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
