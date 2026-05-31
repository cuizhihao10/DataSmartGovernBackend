/**
 * @Author : Cui
 * @Date: 2026/05/31 18:16
 * @Description DataSmart Govern Backend - AgentAsyncToolWorkerController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.controller;

import com.czh.datasmart.govern.task.common.ApiResponse;
import com.czh.datasmart.govern.task.controller.dto.TaskActorContext;
import com.czh.datasmart.govern.task.controller.support.TaskActorContextResolver;
import com.czh.datasmart.govern.task.service.agent.AgentAsyncToolDispatchOnceResult;
import com.czh.datasmart.govern.task.service.agent.AgentAsyncToolDispatchOnceService;
import com.czh.datasmart.govern.task.service.agent.AgentAsyncToolExecutionPreparationService;
import com.czh.datasmart.govern.task.service.agent.AgentAsyncToolResolvedPayload;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 异步工具 worker 内部控制器。
 *
 * <p>4.51 阶段该控制器只提供 payloadReference 解析预检；4.52 阶段增加 dispatch-once 手动调度入口。
 * 两个入口都属于内部控制面，生产环境必须限制为服务账号、内网网关或服务网格访问，不能暴露给普通用户。</p>
 *
 * <p>设计上仍然坚持“Controller 薄、Service 厚”：Controller 只解析 Header 并返回统一响应，
 * 任务认领、payload 校验、白名单适配器选择、下游调用、任务完成/失败/延迟回写全部放在 Service 层。
 * 这样后续接入定时 worker、并发 worker 池或运维补偿台时，可以复用同一套业务动作。</p>
 */
@RestController
@RequestMapping("/internal/agent-async-tool-tasks")
@RequiredArgsConstructor
public class AgentAsyncToolWorkerController {

    private final AgentAsyncToolExecutionPreparationService preparationService;
    private final AgentAsyncToolDispatchOnceService dispatchOnceService;
    private final TaskActorContextResolver actorContextResolver;

    /**
     * 解析并预检指定任务的 Agent 工具参数载荷。
     *
     * @param taskId task-management 中的任务 ID。
     * @param request 原始请求，用于解析平台上下文 Header。
     * @return payloadReference 解析结果和执行前校验摘要。
     */
    @PostMapping("/{taskId}/payload/resolve")
    public ResponseEntity<ApiResponse<AgentAsyncToolResolvedPayload>> resolvePayload(
            @PathVariable("taskId") Long taskId,
            HttpServletRequest request) {
        TaskActorContext actorContext = actorContextResolver.resolve(request);
        AgentAsyncToolResolvedPayload payload = preparationService.preparePayload(taskId, actorContext);
        return ResponseEntity.ok(ApiResponse.success("Agent 异步工具载荷预检完成", payload));
    }

    /**
     * 手动触发一次 Agent 异步工具 worker 调度。
     *
     * <p>该入口会真实认领并执行一条 `AGENT_ASYNC_TOOL` 任务，因此默认受配置开关保护：
     * `enabled=false` 或 `dryRunOnly=true` 时都会被服务层拒绝。后续如果接入后台定时 worker，也应该复用同一个
     * Service 方法，确保手动调试和自动调度使用相同的白名单、幂等和状态回写逻辑。</p>
     */
    @PostMapping("/dispatch-once")
    public ResponseEntity<ApiResponse<AgentAsyncToolDispatchOnceResult>> dispatchOnce(HttpServletRequest request) {
        TaskActorContext actorContext = actorContextResolver.resolve(request);
        AgentAsyncToolDispatchOnceResult result = dispatchOnceService.dispatchOnce(actorContext);
        return ResponseEntity.ok(ApiResponse.success("Agent 异步工具 worker 单次调度完成", result));
    }
}
