/**
 * @Author : Cui
 * @Date: 2026/06/03 23:20
 * @Description DataSmart Govern Backend - AgentToolSandboxVerdict.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.tool.sandbox;

import java.util.List;

/**
 * Agent 工具调用沙箱判定结果。
 *
 * <p>该对象是沙箱策略层输出的“低敏治理事实”，用于两类场景：</p>
 * <p>1. 执行入口硬拦截：{@code AgentToolExecutionGuard} 会读取 verdict.allowed，
 * 如果为 false，就在真正调用下游工具前拒绝执行；</p>
 * <p>2. 前端/运维/学习诊断：查询接口可以展示 issueCodes、reasons 和 recommendedActions，
 * 让用户理解工具为什么被允许、被阻断，或者需要走审批/异步任务。</p>
 *
 * <p>为什么不用一个简单 boolean：
 * 商业化 Agent 的工具调用风险来自很多维度，例如注册表漂移、目标服务伪造、参数过大、超时过长、
 * 非幂等重试、敏感字段未审批等。如果只返回 true/false，前端无法解释，运维无法排障，用户也学不到
 * 真实产品里“为什么不能让模型直接调用工具”。</p>
 *
 * @param auditId 工具审计 ID，用于关联执行、审批和结果查询。
 * @param toolCode 工具编码，例如 datasource.metadata.read。
 * @param sandboxEnabled 本次判定是否真的启用了沙箱；关闭沙箱时 allowed 会为 true，但 reasons 会明确说明风险边界。
 * @param allowed 是否允许继续进入真实工具执行入口。
 * @param isolationMode 当前工具应落入的执行隔离模式，例如 READ_ONLY_SYNC、APPROVAL_BOUND、ASYNC_TASK_BOUND。
 * @param riskLevel 工具风险等级快照。
 * @param executionMode 工具执行模式快照。
 * @param targetService 工具目标服务快照。
 * @param argumentBytes 当前计划参数序列化后的近似字节数。
 * @param maxArgumentBytes 当前沙箱允许的参数字节上限。
 * @param timeoutMs 工具目录声明的超时时间；目录缺失时可能为空。
 * @param maxSyncTimeoutMs 同步工具最大允许超时时间。
 * @param maxRetries 工具目录声明的最大重试次数；目录缺失时可能为空。
 * @param issueCodes 机器可读问题码，便于前端、告警和测试稳定判断。
 * @param reasons 面向用户、审计员和研发学习的中文原因说明。
 * @param recommendedActions 面向下一步处理的建议动作。
 */
public record AgentToolSandboxVerdict(String auditId,
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
