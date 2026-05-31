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
 * <p>该控制器当前只提供“解析 payloadReference 并返回预检结果”的入口。
 * 它不认领任务、不调用业务工具、不把任务标记成功，也不会回写 agent-runtime。
 * 这个克制很重要：在工具适配器、权限策略和状态回写尚未完整之前，贸然自动执行会把系统变成 demo 式黑盒。</p>
 *
 * <p>生产部署要求：</p>
 * <p>1. `/internal/**` 只能在内网、gateway 内部路由或服务网格中访问；</p>
 * <p>2. 调用方必须带 SERVICE_ACCOUNT、OPERATOR 或 PLATFORM_ADMINISTRATOR 角色 Header；</p>
 * <p>3. 后续真正 worker 应复用 Service 层动作，而不是依赖 HTTP 自调用；</p>
 * <p>4. 该接口返回 planArguments，调用方和日志系统都必须避免打印敏感字段值。</p>
 */
@RestController
@RequestMapping("/internal/agent-async-tool-tasks")
@RequiredArgsConstructor
public class AgentAsyncToolWorkerController {

    private final AgentAsyncToolExecutionPreparationService preparationService;
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
}
