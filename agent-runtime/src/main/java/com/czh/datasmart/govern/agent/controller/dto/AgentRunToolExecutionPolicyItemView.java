/**
 * @Author : Cui
 * @Date: 2026/05/29 18:43
 * @Description DataSmart Govern Backend - AgentRunToolExecutionPolicyItemView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.time.Instant;
import java.util.List;

/**
 * 单条工具执行策略预检视图。
 *
 * <p>该 DTO 面向前端、Python AI Runtime 和未来自动执行器，用来解释某个工具计划“现在能不能执行、为什么不能执行、
 * 需要谁处理、是否会阻塞整个 Run”。它刻意不返回工具输出明细，因为输出明细已经由结果查询接口和资源准入链路负责。
 *
 * @param auditId 工具执行审计 ID，调用审批、执行、结果查询等接口时都用它定位同一条工具计划。
 * @param toolCode 工具编码，例如 datasource.metadata.read、task.create，用于前端展示和策略诊断。
 * @param state 当前审计状态，来自 AgentToolExecutionState。
 * @param executionMode 工具执行模式，决定它适合同步执行、异步任务、草稿模式还是审批后执行。
 * @param riskLevel 工具风险等级，前端可以据此展示风险标签，策略层也会据此控制自动化程度。
 * @param requiresApproval 工具是否显式要求审批。
 * @param readOnly 是否只读；非只读工具即使风险等级较低，也不应轻易自动执行。
 * @param idempotent 是否幂等；失败后是否能自动重试主要依赖该字段。
 * @param decision 策略决策，表达下一步应该自动执行、等待审批、等待参数、等待异步执行器或阻断。
 * @param autoExecutable 是否可作为当前批次自动执行候选；这只是候选标记，不会在查询接口里触发副作用。
 * @param requiresHumanAction 是否需要人类介入，例如审批、补参数、人工复核失败。
 * @param blocksRun 是否阻塞当前 Run 继续自动推进。
 * @param sandboxAllowed 工具调用沙箱是否允许该工具进入真实执行入口。它是执行前安全预检结论，不代表工具已经执行。
 * @param sandboxIsolationMode 沙箱建议隔离模式，例如 READ_ONLY_SYNC、APPROVAL_BOUND、ASYNC_TASK_BOUND、BLOCKED。
 * @param sandboxIssueCodes 沙箱机器可读问题码，便于前端、运维、测试和后续告警稳定识别阻断原因。
 * @param sandboxReasons 沙箱中文原因说明。该字段只返回低敏解释，不返回完整工具参数、SQL、样本数据或 prompt。
 * @param sandboxRecommendedActions 沙箱推荐动作，例如补工具目录、补审批、拆异步任务、修复非幂等重试配置。
 * @param reasons 产生该决策的原因列表，面向学习、审计和排障。
 * @param recommendedActions 推荐下一步动作，面向前端按钮、运营处理和 Python 编排器。
 */
public record AgentRunToolExecutionPolicyItemView(String auditId,
                                                  String toolCode,
                                                  String state,
                                                  String executionMode,
                                                  String riskLevel,
                                                  Boolean requiresApproval,
                                                  Boolean readOnly,
                                                  Boolean idempotent,
                                                  String decision,
                                                  Boolean autoExecutable,
                                                  Boolean requiresHumanAction,
                                                  Boolean blocksRun,
                                                  Boolean sandboxAllowed,
                                                  String sandboxIsolationMode,
                                                  List<String> sandboxIssueCodes,
                                                  List<String> sandboxReasons,
                                                  List<String> sandboxRecommendedActions,
                                                  /**
                                                   * 运行时保护是否允许该工具在当前容量与目标服务健康状态下进入真实执行入口。
                                                   *
                                                   * <p>该字段和 sandboxAllowed 的边界不同：sandboxAllowed 回答“工具计划是否安全”，
                                                   * runtimeProtectionAllowed 回答“现在是否适合执行”。例如参数安全、权限满足，
                                                   * 但目标服务已熔断或当前租户并发过高时，该字段会返回 false。</p>
                                                   */
                                                  Boolean runtimeProtectionAllowed,
                                                  /**
                                                   * 当前 JVM 内所有工具执行的 in-flight 数量。
                                                   *
                                                   * <p>第一阶段这是单实例视角，主要用于前端、Python Runtime 和运维台解释暂缓执行原因；
                                                   * 多实例生产环境后续应迁移为 Redis/DB/Quota Center 共享计数。</p>
                                                   */
                                                  Integer runtimeGlobalInFlight,
                                                  /**
                                                   * 当前租户在本 JVM 内的工具执行 in-flight 数量，用于解释租户级公平性和套餐配额边界。
                                                   */
                                                  Integer runtimeTenantInFlight,
                                                  /**
                                                   * 当前目标服务在本 JVM 内的工具执行 in-flight 数量，用于避免 Agent 把同一个下游微服务压垮。
                                                   */
                                                  Integer runtimeTargetServiceInFlight,
                                                  Integer runtimeMaxGlobalInFlight,
                                                  Integer runtimeMaxTenantInFlight,
                                                  Integer runtimeMaxTargetServiceInFlight,
                                                  Boolean runtimeCircuitOpen,
                                                  Instant runtimeCircuitOpenUntil,
                                                  Integer runtimeConsecutiveFailures,
                                                  List<String> runtimeProtectionIssueCodes,
                                                  List<String> runtimeProtectionReasons,
                                                  List<String> runtimeProtectionRecommendedActions,
                                                  List<String> reasons,
                                                  List<String> recommendedActions) {
}
