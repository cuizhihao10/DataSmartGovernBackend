/**
 * @Author : Cui
 * @Date: 2026/07/16 00:00
 * @Description DataSmart Govern Backend - DeterministicAgentExecutionResultAnswerGeneratorTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.answer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicAgentExecutionResultAnswerGeneratorTest {

    private final DeterministicAgentExecutionResultAnswerGenerator generator =
            new DeterministicAgentExecutionResultAnswerGenerator();

    @Test
    void shouldExplainSuccessfulExecutionWithoutInvokingModelProvider() {
        AgentExecutionAssistantAnswer answer = generator.generate(
                "COMPLETED", 9, 9, 0, List.of(), List.of());

        assertTrue(answer.content().contains("9 个工具节点已全部执行成功"));
        assertEquals("DETERMINISTIC_FALLBACK", answer.mode());
        assertEquals("RESERVED_NOT_INVOKED", answer.modelProviderStatus());
    }

    @Test
    void shouldExplainPartialFailureFromControlPlaneFacts() {
        AgentExecutionAssistantAnswer answer = generator.generate(
                "FAILED", 9, 5, 1, List.of(), List.of("RETRY_FAILED_TOOL"));

        assertTrue(answer.content().contains("成功 5 个，失败 1 个"));
        assertTrue(answer.content().contains("修复配置或权限问题"));
    }
}
