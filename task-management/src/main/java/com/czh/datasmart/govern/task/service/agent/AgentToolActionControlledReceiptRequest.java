/**
 * @Author : Cui
 * @Date: 2026/06/11 22:20
 * @Description DataSmart Govern Backend - AgentToolActionControlledReceiptRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

import java.util.List;

/**
 * task-management 发送给 agent-runtime 的受控工具动作 dry-run receipt 请求。
 *
 * <p>该 record 是 task-management 模块内的 HTTP 契约副本，不直接依赖 agent-runtime 的 Java DTO。
 * 微服务之间应该通过 JSON 字段契约通信，而不是跨模块共享编译期类型；这样未来拆分仓库、生成 OpenAPI
 * 或者把 agent-runtime 换成独立部署单元时，task-management 不需要整体重编译。</p>
 *
 * <p>字段只包含低敏治理事实，不包含真实工具参数、payload body、SQL、prompt、样本数据、模型输出、
 * 凭证或内部 endpoint。即便它是内部请求，也要按“可能进入日志、事件和审计系统”的标准设计。</p>
 */
public record AgentToolActionControlledReceiptRequest(
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
