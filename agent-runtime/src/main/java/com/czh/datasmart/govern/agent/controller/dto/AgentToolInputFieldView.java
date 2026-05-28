/**
 * @Author : Cui
 * @Date: 2026/05/13 23:24
 * @Description DataSmart Govern Backend - AgentToolInputFieldView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

/**
 * Agent 工具输入字段视图。
 *
 * <p>该视图描述调用工具前需要准备哪些结构化参数。
 * 它面向三类使用方：
 * 1. 前端：根据字段定义渲染表单或补全提示；
 * 2. Agent 编排器：根据字段定义从会话上下文中抽取参数；
 * 3. 审计与安全：判断某个字段是否可能携带资源 ID、项目 ID、SQL、导出范围等高风险信息。
 */
public record AgentToolInputFieldView(String name,
                                      String type,
                                      Boolean required,
                                      String description,
                                      String example) {
}
