/**
 * @Author : Cui
 * @Date: 2026/05/31 14:20
 * @Description DataSmart Govern Backend - AgentAsyncTaskCommandPlanItemView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * 单个 ASYNC_TASK 工具对应的异步命令草案。
 *
 * <p>该 DTO 是面向前端、Python Runtime、审计台和未来 dispatcher 的“安全视图”。
 * 它只暴露跨服务路由和治理元数据，不返回工具参数值、SQL、文件路径、凭据或样本内容。
 * 真正投递 Kafka 时，dispatcher 应在服务内部按 auditId 读取受控参数快照，再做权限复核和敏感字段处理。</p>
 *
 * @param auditId 工具执行审计 ID，是命令草案与 Agent 工具事实的关联键。
 * @param toolCode 工具编码，例如 data-sync.execute、quality.scan.start。
 * @param state 当前工具审计状态。
 * @param policyDecision Run 级策略服务给出的决策；只有 WAITING_ASYNC_EXECUTOR 才能成为下发候选。
 * @param dispatchable 是否具备进入后续 dispatcher 的条件；当前接口本身不会真正投递。
 * @param commandId 根据 sessionId/runId/auditId 稳定生成的命令 ID，用于日志、回放和消费去重。
 * @param idempotencyKey 跨服务幂等键，未来 task-management 消费者必须据此去重。
 * @param commandType 命令类型，当前固定为 AGENT_TOOL_ASYNC_TASK_REQUESTED。
 * @param dispatchChannel 建议投递通道，当前固定为 KAFKA_COMMAND。
 * @param commandTopic 建议 Kafka topic。
 * @param consumerService 建议消费者模块，默认 task-management。
 * @param targetService 工具最终面向的业务模块，例如 datasource-management、data-quality、data-sync。
 * @param targetEndpoint 工具目录中声明的目标端点模板。
 * @param tenantId 租户边界。
 * @param projectId 项目边界。
 * @param workspaceId 工作空间边界。
 * @param actorId 发起者，用于后续服务到服务授权和审计。
 * @param traceId 链路追踪 ID。
 * @param safeAutomaticRetry 工具是否声明幂等；只有幂等工具才适合自动重试。
 * @param argumentNames 参数名快照，不包含参数值。
 * @param sensitiveArgumentNames 治理提示中声明的敏感参数名，用于 dispatcher 脱敏和审批复核。
 * @param reasons 当前能否下发的原因。
 * @param recommendedActions 下一步建议。
 */
public record AgentAsyncTaskCommandPlanItemView(String auditId,
                                                String toolCode,
                                                String state,
                                                String policyDecision,
                                                Boolean dispatchable,
                                                String commandId,
                                                String idempotencyKey,
                                                String commandType,
                                                String dispatchChannel,
                                                String commandTopic,
                                                String consumerService,
                                                String targetService,
                                                String targetEndpoint,
                                                Long tenantId,
                                                Long projectId,
                                                Long workspaceId,
                                                String actorId,
                                                String traceId,
                                                Boolean safeAutomaticRetry,
                                                List<String> argumentNames,
                                                List<String> sensitiveArgumentNames,
                                                List<String> reasons,
                                                List<String> recommendedActions) {
}
