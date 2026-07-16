/**
 * @Author : Cui
 * @Date: 2026/07/16 00:00
 * @Description DataSmart Govern Backend - AgentExecutionAssistantAnswer.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.answer;

/**
 * Agent 工具执行结束后的用户答复。
 *
 * @param content 面向用户的低敏结果说明，不允许包含工具原始输出、凭据或数据库样本
 * @param mode 答复生成模式；当前为确定性兜底，未来可替换为受控模型 Provider
 * @param modelProviderStatus 模型接口状态，用于明确当前答复是否实际调用了模型
 */
public record AgentExecutionAssistantAnswer(
        String content,
        String mode,
        String modelProviderStatus) {
}
