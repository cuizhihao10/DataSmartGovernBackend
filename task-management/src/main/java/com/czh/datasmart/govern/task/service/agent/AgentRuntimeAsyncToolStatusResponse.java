/**
 * @Author : Cui
 * @Date: 2026/05/31 23:59
 * @Description DataSmart Govern Backend - AgentRuntimeAsyncToolStatusResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

/**
 * agent-runtime 异步工具状态回调响应。
 *
 * <p>task-management 只关心回调是否被接受以及当前审计状态，完整审计详情继续由 agent-runtime 的查询接口提供。</p>
 */
public record AgentRuntimeAsyncToolStatusResponse(
        boolean accepted,
        String auditId,
        String state,
        String message) {
}
