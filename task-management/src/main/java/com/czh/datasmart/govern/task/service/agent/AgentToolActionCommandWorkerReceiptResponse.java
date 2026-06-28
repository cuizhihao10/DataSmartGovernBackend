/**
 * @Author : Cui
 * @Date: 2026/06/28 18:50
 * @Description DataSmart Govern Backend - AgentToolActionCommandWorkerReceiptResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

/**
 * agent-runtime command worker receipt 接收响应副本。
 *
 * <p>task-management 只关心回执是否被接受、是否幂等重复、事件类型和 sideEffectExecuted 摘要。
 * 完整 runtime event、索引和恢复事实包继续由 agent-runtime 查询接口负责。</p>
 */
public record AgentToolActionCommandWorkerReceiptResponse(
        boolean accepted,
        boolean duplicate,
        String identityKey,
        String eventType,
        String outcome,
        boolean sideEffectExecuted,
        String message
) {
}
