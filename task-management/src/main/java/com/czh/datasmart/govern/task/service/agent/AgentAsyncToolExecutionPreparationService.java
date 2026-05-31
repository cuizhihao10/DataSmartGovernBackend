/**
 * @Author : Cui
 * @Date: 2026/05/31 18:16
 * @Description DataSmart Govern Backend - AgentAsyncToolExecutionPreparationService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

import com.czh.datasmart.govern.task.controller.dto.TaskActorContext;
import com.czh.datasmart.govern.task.entity.Task;
import com.czh.datasmart.govern.task.service.TaskService;
import com.czh.datasmart.govern.task.service.support.TaskOperationPermissionSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Agent 异步工具执行准备服务。
 *
 * <p>该服务把“谁可以触发预检”“读取哪条任务”“如何解析 payloadReference”串成一个业务动作。
 * 它仍然不执行工具、不认领租约、不回写成功失败，只服务于下一阶段真实 worker 的执行前准备。</p>
 *
 * <p>为什么要有这一层，而不是 Controller 直接调用 resolver：</p>
 * <p>1. Controller 只负责 HTTP 契约和 Header 解析，不应该理解任务权限；</p>
 * <p>2. resolver 只负责 payloadReference 与参数快照一致性，不应该读取数据库任务；</p>
 * <p>3. preparation service 可以复用任务详情的数据范围校验和执行器角色校验；</p>
 * <p>4. 后续自动 worker、人工补偿台和测试接口都可以复用同一业务动作。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentAsyncToolExecutionPreparationService {

    private final TaskService taskService;
    private final TaskOperationPermissionSupport permissionSupport;
    private final AgentAsyncToolPayloadResolver payloadResolver;

    /**
     * 准备指定任务的 Agent 异步工具载荷。
     *
     * @param taskId 任务 ID。
     * @param actorContext 调用方身份上下文，必须具备执行器动作权限。
     * @return 参数解析和一致性校验结果。
     */
    public AgentAsyncToolResolvedPayload preparePayload(Long taskId, TaskActorContext actorContext) {
        if (taskId == null || taskId <= 0) {
            throw new IllegalArgumentException("taskId 必须大于 0");
        }
        permissionSupport.validateExecutorOperationPermission(actorContext);
        Task task = taskService.getTaskDetail(taskId, actorContext);
        String traceId = actorContext == null ? null : actorContext.traceId();
        return payloadResolver.resolve(task, traceId);
    }
}
