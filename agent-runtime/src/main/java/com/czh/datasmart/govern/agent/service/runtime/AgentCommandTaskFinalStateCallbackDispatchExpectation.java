/**
 * @Author : Cui
 * @Date: 2026/08/20 00:00
 * @Description DataSmart Govern Backend - AgentCommandTaskFinalStateCallbackDispatchExpectation.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentCommandTaskFinalStateLatestReceiptView;
import com.czh.datasmart.govern.agent.controller.dto.AgentCommandTaskFinalStateReconciliationResponse;

import java.util.Objects;

/**
 * durable callback job 对即将发送的 task-management 回调提出的不可变预期。
 *
 * <p>worker 领取 job 后会先读一次 Java receipt；dispatch service 为防止使用旧结论，又会在真正发送 HTTP
 * 前重新对账。如果两次读取之间出现新 replay，或者同一 receipt 的 task/run/executor 关联被异常刷新，
 * dispatch service 绝不能先发送新事实、再让 worker 事后发现幂等键不一致。本对象把 job 创建时冻结的
 * 低敏关联字段带到最后一道 HTTP 门前，任何字段漂移都在副作用之前 fail-closed。</p>
 */
public record AgentCommandTaskFinalStateCallbackDispatchExpectation(
        String commandId,
        Long replaySequence,
        Long taskId,
        Long taskRunId,
        String executorId,
        String auditId,
        String tenantId,
        String projectId,
        String actorId,
        String runId,
        String sessionId,
        String toolCode,
        String callbackStatus,
        String idempotencyKey
) {

    /** 从 durable job 创建 dispatch 前置条件，调用方不必重新拼接字段。 */
    public static AgentCommandTaskFinalStateCallbackDispatchExpectation fromJob(
            AgentCommandTaskFinalStateCallbackJob job) {
        Objects.requireNonNull(job, "callback job 不能为空");
        return new AgentCommandTaskFinalStateCallbackDispatchExpectation(
                job.commandId(),
                job.sourceReplaySequence(),
                job.taskId(),
                job.taskRunId(),
                job.executorId(),
                job.auditId(),
                job.tenantId(),
                job.projectId(),
                job.actorId(),
                job.runId(),
                job.sessionId(),
                job.toolCode(),
                job.callbackStatus(),
                job.callbackIdempotencyKey()
        );
    }

    /**
     * 判断 dispatch service 刚完成的最新对账是否仍与 durable job 完全一致。
     *
     * @param reconciliation HTTP 前最后一次 Java 对账结果
     * @param plan 由该对账结果生成、尚未产生副作用的回调计划
     * @return true 表示可以继续发送；false 表示事实已漂移，应由 worker 转入 superseded/补偿
     */
    public boolean matches(AgentCommandTaskFinalStateReconciliationResponse reconciliation,
                           CallbackDispatchPlan plan) {
        if (reconciliation == null || reconciliation.latestReceipt() == null || plan == null) {
            return false;
        }
        AgentCommandTaskFinalStateLatestReceiptView receipt = reconciliation.latestReceipt();
        return Objects.equals(commandId, receipt.commandId())
                && Objects.equals(replaySequence, receipt.replaySequence())
                && Objects.equals(taskId, receipt.taskId())
                && Objects.equals(taskRunId, receipt.taskRunId())
                && Objects.equals(executorId, receipt.executorId())
                && Objects.equals(auditId, receipt.auditId())
                && Objects.equals(tenantId, receipt.tenantId())
                && Objects.equals(projectId, receipt.projectId())
                && Objects.equals(actorId, receipt.actorId())
                && Objects.equals(runId, receipt.runId())
                && Objects.equals(sessionId, receipt.sessionId())
                && Objects.equals(toolCode, receipt.toolCode())
                && Objects.equals(callbackStatus, plan.callbackStatus())
                && Objects.equals(idempotencyKey, plan.idempotencyKey());
    }
}
