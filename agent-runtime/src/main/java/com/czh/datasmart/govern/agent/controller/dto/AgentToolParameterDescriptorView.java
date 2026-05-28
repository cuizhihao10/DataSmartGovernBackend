/**
 * @Author : Cui
 * @Date: 2026/05/23 20:31
 * @Description DataSmart Govern Backend - AgentToolParameterDescriptorView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

/**
 * Agent 工具参数描述。
 *
 * <p>该视图比旧的 `AgentToolInputFieldView` 更适合工具规划：
 * 它不仅说明字段名、类型和是否必填，还说明字段是否敏感、应该如何解析。
 * 例如 projectId 可以由系统注入，datasourceId 可以从用户请求或上下文补齐，SQL 则通常需要显式用户确认。
 *
 * @param name 参数名
 * @param type 参数类型，例如 string、number、boolean、object、array
 * @param required 是否必填
 * @param sensitive 是否敏感，敏感参数进入审批和审计时需要谨慎展示
 * @param resolution 参数解析方式，例如 USER_REQUIRED、CAN_FILL_FROM_CONTEXT、SYSTEM_INJECTED、DERIVED
 * @param description 参数说明
 * @param example 参数示例
 */
public record AgentToolParameterDescriptorView(String name,
                                               String type,
                                               Boolean required,
                                               Boolean sensitive,
                                               String resolution,
                                               String description,
                                               String example) {
}
