/**
 * @Author : Cui
 * @Date: 2026/05/29 22:05
 * @Description DataSmart Govern Backend - AgentRunToolAutoExecutionRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * Run 级同步工具自动执行请求。
 *
 * <p>该请求不是让 Agent “无脑执行所有工具”，而是给受控自动执行器提供额外约束：
 * 调用方可以指定最多执行几个工具、只执行哪些 auditId，也可以使用 dryRun 先查看候选结果。
 * 真正的安全筛选仍由 Java 服务端执行，前端或 Python Runtime 传入 auditIds 只能缩小范围，不能扩大权限。</p>
 *
 * @param auditIds 可选的审计 ID 白名单；为空表示在当前 Run 的全部安全候选中选择。
 * @param maxExecutions 可选的本次最大执行数量；最终值还会受到服务端配置上限约束。
 * @param dryRun 是否只演算候选而不真实执行；适合前端确认页或 Python Runtime 先判断下一步。
 */
public record AgentRunToolAutoExecutionRequest(List<String> auditIds,
                                               Integer maxExecutions,
                                               Boolean dryRun) {
}
