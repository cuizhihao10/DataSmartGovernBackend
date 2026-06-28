/**
 * @Author : Cui
 * @Date: 2026/06/28 23:10
 * @Description DataSmart Govern Backend - AgentToolActionQualityRemediationSubmitRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

/**
 * 质量治理受控命令提交请求。
 *
 * <p>该 DTO 面向 task-management worker 调用 agent-runtime 内部接口时使用。它不携带 payload body、工具参数、
 * SQL、prompt、样本数据或模型输出；worker 只传自己是谁、当前 task/run 是什么，真正的治理任务正文由 agent-runtime
 * 根据 commandId 回查 outbox、confirmation fact 和 payload store 后在服务端内部恢复。</p>
 *
 * @param executorId task-management worker 标识，用于审计和幂等诊断。
 * @param taskId task-management 中承载该 command 的任务 ID，可为空。
 * @param taskRunId task-management 本次认领产生的执行 run ID，可为空。
 * @param idempotencyKey worker 调用本接口的幂等键；为空时服务端使用 commandId 兜底。
 */
public record AgentToolActionQualityRemediationSubmitRequest(
        String executorId,
        Long taskId,
        Long taskRunId,
        String idempotencyKey
) {
}
