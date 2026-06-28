/**
 * @Author : Cui
 * @Date: 2026/06/28 18:50
 * @Description DataSmart Govern Backend - AgentToolActionQualityRemediationSubmitRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

/**
 * task-management 调用 agent-runtime 质量治理受控提交入口的请求副本。
 *
 * <p>该请求故意不包含 payload body。task-management 只告诉 agent-runtime 当前 worker 身份、任务 ID、
 * task execution run ID 和幂等键；agent-runtime 会自行回查 outbox、payload store、审批确认事实并调用
 * data-quality。这样可以避免任务中心持有治理草案正文或工具参数。</p>
 */
public record AgentToolActionQualityRemediationSubmitRequest(
        String executorId,
        Long taskId,
        Long taskRunId,
        String idempotencyKey
) {
}
