/**
 * @Author : Cui
 * @Date: 2026/05/14 19:12
 * @Description DataSmart Govern Backend - AgentToolExecutionOutcome.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.tool;

import java.util.Map;

/**
 * Agent 工具执行结果。
 *
 * <p>这是适配器返回给执行框架的内部结果对象。
 * 它不直接等同于 HTTP 响应，因为执行框架还需要把结果写回审计状态，
 * 再包装成 `AgentToolExecutionResultView` 返回给前端或调用方。
 *
 * @param success 工具是否执行成功。
 * @param message 面向审计和前端的执行说明。
 * @param output 工具输出摘要。第一阶段使用 Map，便于承载不同工具的差异化结果。
 * @param errorCode 可选错误码，用于后续告警、报表和失败分类。
 */
public record AgentToolExecutionOutcome(boolean success,
                                        String message,
                                        Map<String, Object> output,
                                        String errorCode) {

    public static AgentToolExecutionOutcome succeeded(String message, Map<String, Object> output) {
        return new AgentToolExecutionOutcome(true, message, output == null ? Map.of() : output, null);
    }

    public static AgentToolExecutionOutcome failed(String errorCode, String message) {
        return new AgentToolExecutionOutcome(false, message, Map.of(), errorCode);
    }
}
