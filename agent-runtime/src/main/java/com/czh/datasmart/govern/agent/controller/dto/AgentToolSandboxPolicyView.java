/**
 * @Author : Cui
 * @Date: 2026/06/03 23:25
 * @Description DataSmart Govern Backend - AgentToolSandboxPolicyView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * Agent 单个工具调用沙箱策略视图。
 *
 * <p>该 DTO 面向前端、运维诊断、Python Runtime 和学习复盘，回答“某个工具计划在真实执行前是否通过沙箱预检”。</p>
 *
 * <p>为什么单独提供 sandbox-policy，而不是只依赖 execution-policy：
 * execution-policy 关注的是 Run 级推进状态，例如等待审批、自动执行候选、异步任务候选；
 * sandbox-policy 关注的是更底层的执行隔离与安全条件，例如工具目录是否一致、目标服务是否允许、参数体量是否过大、
 * 非幂等工具是否错误配置了重试、敏感参数是否缺少审批。两者结合后，产品才能既知道“下一步怎么走”，
 * 也知道“为什么这一步从安全角度可执行或不可执行”。</p>
 *
 * @param sessionId Agent 会话 ID，用于确认策略属于哪个工作空间。
 * @param runId Agent Run ID，用于确认策略属于哪一次编排尝试。
 * @param auditId 工具执行审计 ID，用于关联审批、执行和结果查询。
 * @param toolCode 工具编码。
 * @param sandboxEnabled 沙箱是否启用；关闭时 allowed 可能为 true，但仍应视为非生产安全状态。
 * @param allowed 是否允许继续进入真实执行入口。
 * @param isolationMode 沙箱建议隔离模式，例如 READ_ONLY_SYNC、APPROVAL_BOUND、ASYNC_TASK_BOUND、BLOCKED。
 * @param riskLevel 工具风险等级。
 * @param executionMode 工具执行模式。
 * @param targetService 工具目标服务。
 * @param argumentBytes 工具计划参数近似字节数。
 * @param maxArgumentBytes 当前沙箱参数体量上限。
 * @param timeoutMs 工具目录声明超时时间。
 * @param maxSyncTimeoutMs 同步工具最大允许超时时间。
 * @param maxRetries 工具目录声明最大重试次数。
 * @param issueCodes 机器可读问题码。
 * @param reasons 中文原因说明，不包含完整敏感参数。
 * @param recommendedActions 推荐下一步动作。
 */
public record AgentToolSandboxPolicyView(String sessionId,
                                         String runId,
                                         String auditId,
                                         String toolCode,
                                         Boolean sandboxEnabled,
                                         Boolean allowed,
                                         String isolationMode,
                                         String riskLevel,
                                         String executionMode,
                                         String targetService,
                                         Integer argumentBytes,
                                         Integer maxArgumentBytes,
                                         Long timeoutMs,
                                         Long maxSyncTimeoutMs,
                                         Integer maxRetries,
                                         List<String> issueCodes,
                                         List<String> reasons,
                                         List<String> recommendedActions) {
}
