/**
 * @Author : Cui
 * @Date: 2026/05/31 23:59
 * @Description DataSmart Govern Backend - AgentRuntimeAsyncToolStatusRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

import java.util.Map;

/**
 * task-management 回写 agent-runtime 的异步工具状态请求。
 *
 * <p>该 record 与 agent-runtime 的内部回调 DTO 字段保持一致，但放在 task-management 模块内独立定义。
 * 这样两个微服务不会因为直接共享 Java 类而形成编译期耦合，后续拆成独立仓库或通过 OpenAPI/IDL 管理契约时也更自然。</p>
 */
public record AgentRuntimeAsyncToolStatusRequest(
        String commandId,
        Long taskId,
        Long taskRunId,
        String executorId,
        String status,
        String message,
        String errorCode,
        String outputSummary,
        Map<String, Object> output,
        String idempotencyKey) {
}
