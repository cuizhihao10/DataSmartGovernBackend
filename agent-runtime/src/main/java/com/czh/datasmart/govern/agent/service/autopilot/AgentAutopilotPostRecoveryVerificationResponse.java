/**
 * @Author : Cui
 * @Date: 2026/08/13 00:25
 * @Description DataSmart Govern Backend - AgentAutopilotPostRecoveryVerificationResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.autopilot;

import java.util.List;

/**
 * Python Runtime 在真实恢复动作之后返回的低敏 Specialist 复核合同。
 *
 * <p>响应只证明 PRECHECK_AGENT 与 MONITOR_AGENT 是否针对同一 task/execution 完成并持久登记；它不携带
 * 预检查正文、监控日志、模型推理、工具参数或凭据。Java 客户端会复核所有字段，不能仅凭 HTTP 2xx
 * 就提交 Kafka offset。</p>
 */
public record AgentAutopilotPostRecoveryVerificationResponse(
        String schemaVersion,
        String status,
        String eventId,
        Long taskId,
        Long executionId,
        List<String> executedRoles,
        List<String> completedRoles,
        String batchStatus,
        String checkpointThreadId,
        Boolean replayed,
        String payloadPolicy) {

    /** 防止 JSON 可变列表在响应校验期间被外部调用方改写。 */
    public AgentAutopilotPostRecoveryVerificationResponse {
        executedRoles = executedRoles == null ? List.of() : List.copyOf(executedRoles);
        completedRoles = completedRoles == null ? List.of() : List.copyOf(completedRoles);
    }
}
