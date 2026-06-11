/**
 * @Author : Cui
 * @Date: 2026/06/11 22:20
 * @Description DataSmart Govern Backend - AgentToolActionControlledDryRunReceiptRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * task-management 回写 `AGENT_TOOL_ACTION_CONTROLLED` dry-run receipt 的内部请求。
 *
 * <p>该 DTO 不是公开 API 契约，而是 task-management -> agent-runtime 的服务间控制面契约。
 * 它表达“某个受控工具动作任务已经被 dry-run 调度器认领，并完成了执行前治理判断”，但不表达
 * “工具已经真实执行”。因此字段设计刻意只包含低敏摘要：任务 ID、命令 ID、工具编码、outcome、
 * payload store 证据是否存在、payload body 是否已物化、策略版本数量等。</p>
 *
 * <p>这里不允许携带工具实参、SQL、prompt、样本数据、模型输出、凭证、内部 endpoint、payload body
 * 或 artifact 正文。agent-runtime 接收后仍会进行二次清洗，避免内部调用方误把敏感诊断文本写入
 * runtime event timeline。</p>
 *
 * @param commandId task-management 从 Inbox 任务中解析出的受控工具动作命令 ID，用于串联 outbox、inbox、task 与 receipt。
 * @param taskId task-management 任务表主键，便于运维从 Agent timeline 反查任务中心记录。
 * @param taskRunId task-management 本次认领生成的执行 run ID，用于区分同一个任务的多次 dry-run 尝试。
 * @param executorId dry-run 调度器身份，通常是 task-management worker executorId 加专用后缀。
 * @param tenantId 当前任务所属租户 ID，用于 runtime event 数据范围收口。
 * @param projectId 当前任务所属项目 ID，用于 runtime event 数据范围收口。
 * @param actorId 触发 dry-run 的操作者或服务账号 ID；为空时表示只知道服务角色，不知道具体主体。
 * @param taskStatus dry-run 认领时 task-management 看到的任务状态，例如 RUNNING。
 * @param outcome dry-run 结果，例如 FAILED_PRECHECK、DEFERRED_WAITING_PAYLOAD_BODY。
 * @param preCheckPassed true 表示低敏前置复核通过但暂不可执行；false 表示已在执行前阻断。
 * @param sideEffectExecuted dry-run 阶段必须为 false，agent-runtime 会拒绝 true，避免语义漂移。
 * @param message 面向人类的低敏摘要说明，会再次被 agent-runtime 长度限制和敏感片段过滤。
 * @param errorCode 失败或阻断分类码，用于前端、审计和运维聚合。
 * @param auditId 低敏 audit/action 引用 ID；当前新链路不强制要求它对应历史工具审计记录。
 * @param toolCode 工具编码，例如 datasource.metadata.read，只能作为低敏目录标识。
 * @param targetService 当前受控动作声明的目标服务；新链路当前应固定为 agent-runtime 控制面。
 * @param payloadReferenceType payload 引用类型，当前应为 AGENT_PAYLOAD。
 * @param payloadKey `agent-payload:{runId}/{payloadKey}` 中的低敏 key，不包含 payload body。
 * @param payloadStoreEvidence 是否已经看到服务端 payload store 登记与作用域复核证据。
 * @param payloadBodyAvailable payload body 是否已经物化；当前通常为 false。
 * @param workerDispatchEnabled 是否允许旧 worker 调度；新链路必须为 false。
 * @param policyVersionCount 命令携带的策略版本数量，只记录数量，不展开策略正文。
 * @param delegationEvidenceCount 命令携带的低敏委托证据数量，只记录数量，不展开证据正文。
 * @param recommendedActions 面向运维/下一步处理的低敏建议，会限制数量和长度。
 * @param idempotencyKey receipt 幂等键，用于避免同一 dry-run 结果重复进入 timeline。
 */
public record AgentToolActionControlledDryRunReceiptRequest(
        String commandId,
        Long taskId,
        Long taskRunId,
        String executorId,
        Long tenantId,
        Long projectId,
        Long actorId,
        String taskStatus,
        String outcome,
        Boolean preCheckPassed,
        Boolean sideEffectExecuted,
        String message,
        String errorCode,
        String auditId,
        String toolCode,
        String targetService,
        String payloadReferenceType,
        String payloadKey,
        Boolean payloadStoreEvidence,
        Boolean payloadBodyAvailable,
        Boolean workerDispatchEnabled,
        Integer policyVersionCount,
        Integer delegationEvidenceCount,
        List<String> recommendedActions,
        String idempotencyKey
) {
}
