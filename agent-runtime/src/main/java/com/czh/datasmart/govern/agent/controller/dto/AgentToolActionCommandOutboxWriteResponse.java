/**
 * @Author : Cui
 * @Date: 2026/06/07 15:16
 * @Description DataSmart Govern Backend - AgentToolActionCommandOutboxWriteResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * 工具动作 command outbox 写入响应。
 *
 * <p>该响应表示“工具动作 command proposal 已尝试推进到 durable command outbox”。它不是 worker 执行结果，
 * 也不是 task-management 已接单结果。真正的副作用仍要等待 dispatcher 投递、task-management inbox 去重、
 * worker receipt 回写和后续审计链路。</p>
 *
 * <p>响应只返回 outbox 记录安全视图，不返回 payloadJson。payloadJson 虽然是低敏命令信封，但属于服务间命令体，
 * 不应该通过管理台列表或普通前端扩散。</p>
 *
 * @param writerState writer 处理状态，例如 ENQUEUED、DUPLICATE_REUSED、BLOCKED_BY_PROPOSAL。
 * @param enqueued 是否首次写入 outbox。
 * @param duplicate 是否命中 commandId 幂等复用。
 * @param commandId 稳定 command ID，供 dispatcher、task-management inbox 和审计台关联。
 * @param proposalId 来源 command proposal ID。
 * @param graphId 来源执行图 ID。
 * @param contractId 来源 durable action contract ID。
 * @param runId Agent run ID，用于 outbox 分区、查询和容量保护。
 * @param payloadReference 受控载荷引用，仍不是参数正文。
 * @param proposalState proposal 的最终状态。
 * @param summaryReasons 本次写入或阻断原因。
 * @param recommendedActions 下一步建议。
 * @param record outbox 记录安全视图；阻断时为空。
 */
public record AgentToolActionCommandOutboxWriteResponse(
        String writerState,
        Boolean enqueued,
        Boolean duplicate,
        String commandId,
        String proposalId,
        String graphId,
        String contractId,
        String runId,
        String payloadReference,
        String proposalState,
        List<String> summaryReasons,
        List<String> recommendedActions,
        AgentAsyncTaskCommandOutboxRecordView record
) {
}
