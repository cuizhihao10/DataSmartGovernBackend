/**
 * @Author : Cui
 * @Date: 2026/05/31 23:42
 * @Description DataSmart Govern Backend - AgentAsyncToolDispatchOnceService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

import com.czh.datasmart.govern.task.config.AgentAsyncToolWorkerProperties;
import com.czh.datasmart.govern.task.controller.dto.TaskActorContext;
import com.czh.datasmart.govern.task.controller.dto.TaskExecutionCallbackContext;
import com.czh.datasmart.govern.task.controller.dto.TaskExecutionClaimRequest;
import com.czh.datasmart.govern.task.controller.dto.TaskExecutionClaimResult;
import com.czh.datasmart.govern.task.entity.Task;
import com.czh.datasmart.govern.task.service.TaskService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Agent 异步工具 worker 单次调度服务。
 *
 * <p>当前阶段只提供手动触发的 dispatch-once，而不是默认后台定时循环。
 * 这是为了让链路在商业化安全边界内渐进落地：先完成“认领 -> 解析 payloadReference -> 白名单适配器执行 ->
 * 任务完成/失败/延迟”的最小闭环，再逐步接入自动调度、并发池、租户配额、熔断、心跳续租和状态回写。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentAsyncToolDispatchOnceService {

    private static final String OUTCOME_NO_TASK = "NO_TASK";
    private static final String OUTCOME_COMPLETED = "COMPLETED";
    private static final String OUTCOME_DEFERRED = "DEFERRED";
    private static final String OUTCOME_FAILED = "FAILED";

    private final TaskService taskService;
    private final AgentAsyncToolPayloadResolver payloadResolver;
    private final List<AgentAsyncToolExecutor> executors;
    private final AgentRuntimeAsyncToolStatusClient statusClient;
    private final AgentAsyncToolWorkerProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * 认领并执行一条 Agent 异步工具任务。
     *
     * <p>该方法会产生真实副作用：如果适配器执行成功，会把 task-management 任务标记为 SUCCESS；
     * 如果下游临时不可用，会 defer 回队列；如果参数或工具白名单不合法，会 fail 当前任务。
     * 因此它必须显式检查 worker enabled 和 dryRunOnly 开关，防止本地开发环境启动后意外执行 Agent 工具。</p>
     */
    public AgentAsyncToolDispatchOnceResult dispatchOnce(TaskActorContext actorContext) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("Agent 异步工具 worker 未启用，请先配置 datasmart.task-management.agent-async-worker.enabled=true");
        }
        if (properties.isDryRunOnly()) {
            throw new IllegalStateException("Agent 异步工具 worker 当前为 dryRunOnly=true，只允许 payload 预检，不允许真实执行");
        }
        TaskExecutionClaimResult claim = taskService.claimNextTask(claimRequest(), actorContext);
        if (!claim.claimed()) {
            return new AgentAsyncToolDispatchOnceResult(false, null, null, null, OUTCOME_NO_TASK, claim.message(), Map.of());
        }
        Task task = claim.task();
        Long runId = claim.executionRun() == null ? null : claim.executionRun().getId();
        try {
            AgentAsyncToolResolvedPayload payload = payloadResolver.resolve(task, actorContext == null ? null : actorContext.traceId());
            AgentAsyncToolExecutor executor = selectExecutor(payload.toolCode());
            if (!notifyRunningStatus(task, runId, payload, actorContext)) {
                return new AgentAsyncToolDispatchOnceResult(true, task.getId(), runId, payload.toolCode(),
                        OUTCOME_DEFERRED, "agent-runtime RUNNING 状态回写失败，任务已退避等待补偿。", Map.of());
            }
            AgentAsyncToolExecutionResult executionResult = executor.execute(payload);
            TaskExecutionCallbackContext callbackContext = callbackContext(runId, payload, actorContext, executionResult);
            if (executionResult.success()) {
                if (!notifyTerminalStatus(task, runId, payload, actorContext, executionResult, "SUCCEEDED", null)) {
                    return new AgentAsyncToolDispatchOnceResult(true, task.getId(), runId, payload.toolCode(),
                            OUTCOME_DEFERRED, "业务执行已成功，但 agent-runtime 状态回写失败，任务已退避等待补偿。", executionResult.output());
                }
                taskService.completeTask(task.getId(), toJson(executionResult.output()), callbackContext);
                return new AgentAsyncToolDispatchOnceResult(true, task.getId(), runId, payload.toolCode(),
                        OUTCOME_COMPLETED, executionResult.message(), executionResult.output());
            }
            if (executionResult.retryable()) {
                if (!notifyTerminalStatus(task, runId, payload, actorContext, executionResult, "DEFERRED", null)) {
                    return new AgentAsyncToolDispatchOnceResult(true, task.getId(), runId, payload.toolCode(),
                            OUTCOME_DEFERRED, "工具临时失败且 agent-runtime 状态回写失败，任务已退避等待补偿。", executionResult.output());
                }
                taskService.deferTask(task.getId(), executionResult.message(), 30, callbackContext);
                return new AgentAsyncToolDispatchOnceResult(true, task.getId(), runId, payload.toolCode(),
                        OUTCOME_DEFERRED, executionResult.message(), executionResult.output());
            }
            if (!notifyTerminalStatus(task, runId, payload, actorContext, executionResult,
                    "FAILED", "AGENT_ASYNC_TOOL_FATAL_FAILURE")) {
                return new AgentAsyncToolDispatchOnceResult(true, task.getId(), runId, payload.toolCode(),
                        OUTCOME_DEFERRED, "工具失败状态尚未成功回写 agent-runtime，任务已退避等待补偿。", executionResult.output());
            }
            taskService.failTask(task.getId(), executionResult.message(), callbackContext);
            return new AgentAsyncToolDispatchOnceResult(true, task.getId(), runId, payload.toolCode(),
                    OUTCOME_FAILED, executionResult.message(), executionResult.output());
        } catch (RuntimeException exception) {
            TaskExecutionCallbackContext callbackContext = new TaskExecutionCallbackContext(
                    runId,
                    properties.getExecutorId(),
                    "agent-async-tool:" + task.getId() + ":failed",
                    actorContext
            );
            taskService.failTask(task.getId(), exception.getMessage(), callbackContext);
            return new AgentAsyncToolDispatchOnceResult(true, task.getId(), runId, null,
                    OUTCOME_FAILED, exception.getMessage(), Map.of());
        }
    }

    private TaskExecutionClaimRequest claimRequest() {
        TaskExecutionClaimRequest request = new TaskExecutionClaimRequest();
        request.setExecutorId(properties.getExecutorId());
        request.setTaskType(AgentAsyncToolPayloadResolver.TASK_TYPE);
        request.setLeaseSeconds(properties.getClaimLeaseSeconds());
        return request;
    }

    private AgentAsyncToolExecutor selectExecutor(String toolCode) {
        return executors.stream()
                .filter(executor -> executor.supports(toolCode))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("未找到 Agent 异步工具白名单适配器，toolCode=" + toolCode));
    }

    private TaskExecutionCallbackContext callbackContext(Long runId,
                                                         AgentAsyncToolResolvedPayload payload,
                                                         TaskActorContext actorContext,
                                                         AgentAsyncToolExecutionResult executionResult) {
        String terminal = executionResult.success() ? "complete" : executionResult.retryable() ? "defer" : "fail";
        return new TaskExecutionCallbackContext(
                runId,
                properties.getExecutorId(),
                "agent-async-tool:" + payload.commandId() + ":" + terminal,
                actorContext
        );
    }

    private boolean notifyRunningStatus(Task task,
                                        Long runId,
                                        AgentAsyncToolResolvedPayload payload,
                                        TaskActorContext actorContext) {
        try {
            statusClient.notifyStatus(payload, runId, "RUNNING",
                    "Agent 异步工具任务已被 task-management worker 领取，开始执行白名单工具适配器。", null, Map.of());
            return true;
        } catch (RuntimeException callbackException) {
            TaskExecutionCallbackContext callbackContext = new TaskExecutionCallbackContext(
                    runId,
                    properties.getExecutorId(),
                    "agent-async-tool:" + payload.commandId() + ":running-callback-deferred",
                    actorContext
            );
            taskService.deferTask(task.getId(), callbackException.getMessage(), 30, callbackContext);
            return false;
        }
    }

    /**
     * 回写执行完成后的 Agent 审计状态，并在回写失败时优先 defer 当前任务。
     *
     * <p>这里的顺序非常关键：对于 SUCCEEDED，必须先让 agent-runtime 接受成功回调，再 complete task-management 任务。
     * 否则会出现“业务已经执行成功、任务中心也成功，但 Agent 会话仍停留在 EXECUTING/PLANNED”的割裂状态。
     * 如果回写失败，我们让任务 defer，由下一轮 worker 依赖下游幂等键补偿，而不是静默吞掉状态同步失败。</p>
     */
    private boolean notifyTerminalStatus(Task task,
                                         Long runId,
                                         AgentAsyncToolResolvedPayload payload,
                                         TaskActorContext actorContext,
                                         AgentAsyncToolExecutionResult executionResult,
                                         String status,
                                         String errorCode) {
        try {
            statusClient.notifyStatus(payload, runId, status, executionResult.message(), errorCode, executionResult.output());
            return true;
        } catch (RuntimeException callbackException) {
            TaskExecutionCallbackContext callbackContext = new TaskExecutionCallbackContext(
                    runId,
                    properties.getExecutorId(),
                    "agent-async-tool:" + payload.commandId() + ":status-callback-deferred",
                    actorContext
            );
            taskService.deferTask(task.getId(), callbackException.getMessage(), 30, callbackContext);
            return false;
        }
    }

    private String toJson(Map<String, Object> output) {
        try {
            return objectMapper.writeValueAsString(output);
        } catch (JsonProcessingException exception) {
            return String.valueOf(output);
        }
    }
}
