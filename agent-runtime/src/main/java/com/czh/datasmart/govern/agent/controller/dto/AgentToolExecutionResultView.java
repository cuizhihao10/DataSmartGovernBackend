/**
 * @Author : Cui
 * @Date: 2026/05/14 19:12
 * @Description DataSmart Govern Backend - AgentToolExecutionResultView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.Map;

/**
 * Agent 工具执行结果视图。
 *
 * <p>该视图用于工具执行接口返回。
 * 它同时包含两类信息：
 * 1. `audit`：工具审计记录的最新状态，例如 EXECUTING、SUCCEEDED、FAILED；
 * 2. `output`：工具适配器返回的结构化结果摘要，例如元数据表数量、远程响应体、提示信息。
 *
 * <p>为什么不直接把 output 写死成某个 DTO？
 * Agent 工具会覆盖数据源、质量、任务、权限、资产、合规等多个领域。
 * 第一阶段使用 Map 可以让执行框架先稳定下来，后续对高频工具再沉淀成强类型 DTO。
 */
public record AgentToolExecutionResultView(AgentToolExecutionAuditView audit,
                                           Map<String, Object> output) {
}
