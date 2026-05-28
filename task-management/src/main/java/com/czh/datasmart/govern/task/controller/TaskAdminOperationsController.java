package com.czh.datasmart.govern.task.controller;

import com.czh.datasmart.govern.task.common.ApiResponse;
import com.czh.datasmart.govern.task.controller.dto.TaskAdminActionRequest;
import com.czh.datasmart.govern.task.controller.dto.TaskPriorityOverrideRequest;
import com.czh.datasmart.govern.task.controller.support.TaskActorContextResolver;
import com.czh.datasmart.govern.task.entity.Task;
import com.czh.datasmart.govern.task.service.TaskService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @Author : Cui
 * @Date: 2026/05/07 21:40
 * @Description DataSmart Govern Backend - TaskAdminOperationsController.java
 * @Version:1.0.0
 *
 * 任务管理员强控操作控制器。
 *
 * <p>管理员强控接口与普通任务生命周期接口的业务语义不同：
 * 普通接口强调用户按标准状态机操作，管理员接口强调事故止损、人工恢复、优先级干预和运维审计。
 * 将它们拆成独立 Controller，可以让普通 TaskController 保持资源入口清晰，
 * 同时为后续补充审批、双人复核、高风险操作告警、影响面分析和操作回放预留更清楚的扩展面。</p>
 */
@RestController
@RequestMapping("/tasks")
@RequiredArgsConstructor
public class TaskAdminOperationsController {

    private final TaskService taskService;
    private final TaskActorContextResolver actorContextResolver;

    /**
     * 管理员强制暂停任务。
     *
     * <p>普通暂停只允许 RUNNING -> PAUSED；强制暂停允许运营在事故场景下暂停 PENDING 任务，
     * 防止任务继续进入调度队列。原因会进入执行日志，便于后续审计和复盘。</p>
     */
    @PostMapping("/{id}/admin/pause")
    public ResponseEntity<ApiResponse<Task>> forcePauseTask(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) TaskAdminActionRequest request,
            HttpServletRequest httpRequest) {
        Task task = taskService.forcePauseTask(id, request == null ? null : request.getReason(),
                actorContextResolver.resolve(httpRequest));
        return ResponseEntity.ok(ApiResponse.success("任务已由管理员强制暂停", task));
    }

    /**
     * 管理员恢复任务。
     *
     * <p>当前恢复会把任务放回 PENDING，而不是直接改成 RUNNING。
     * 这是为了保证 RUNNING 只代表执行器真正接手，避免管理接口伪造执行态。</p>
     */
    @PostMapping("/{id}/admin/resume")
    public ResponseEntity<ApiResponse<Task>> forceResumeTask(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) TaskAdminActionRequest request,
            HttpServletRequest httpRequest) {
        Task task = taskService.forceResumeTask(id, request == null ? null : request.getReason(),
                actorContextResolver.resolve(httpRequest));
        return ResponseEntity.ok(ApiResponse.success("任务已由管理员恢复到待调度状态", task));
    }

    /**
     * 管理员强制取消任务。
     *
     * <p>该接口用于事故止损和人工处置，例如下游系统故障、客户撤销需求、任务长时间卡住等场景。</p>
     */
    @PostMapping("/{id}/admin/cancel")
    public ResponseEntity<ApiResponse<Task>> forceCancelTask(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) TaskAdminActionRequest request,
            HttpServletRequest httpRequest) {
        Task task = taskService.forceCancelTask(id, request == null ? null : request.getReason(),
                actorContextResolver.resolve(httpRequest));
        return ResponseEntity.ok(ApiResponse.success("任务已由管理员强制取消", task));
    }

    /**
     * 管理员强制重试任务。
     *
     * <p>当外部依赖修复、执行器恢复或数据权限修复后，管理员可以把失败/取消/暂停任务重新放回待调度队列。
     * 如确需超过 maxRetryCount，可传入 ignoreRetryLimit=true，并在 reason 中说明原因。</p>
     */
    @PostMapping("/{id}/admin/retry")
    public ResponseEntity<ApiResponse<Task>> forceRetryTask(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) TaskAdminActionRequest request,
            HttpServletRequest httpRequest) {
        Task task = taskService.forceRetryTask(id,
                request == null ? null : request.getReason(),
                request == null ? null : request.getIgnoreRetryLimit(),
                actorContextResolver.resolve(httpRequest));
        return ResponseEntity.ok(ApiResponse.success("任务已由管理员强制重试", task));
    }

    /**
     * 管理员覆盖任务优先级。
     *
     * <p>当前只更新任务快照和日志；未来接入真实队列后，这里还应触发队列重排、
     * 租户公平性检查和 SLA 风险告警。</p>
     */
    @PutMapping("/{id}/admin/priority")
    public ResponseEntity<ApiResponse<Task>> overridePriority(
            @PathVariable Long id,
            @Valid @RequestBody TaskPriorityOverrideRequest request,
            HttpServletRequest httpRequest) {
        Task task = taskService.overridePriority(id, request.getPriority(), request.getReason(),
                actorContextResolver.resolve(httpRequest));
        return ResponseEntity.ok(ApiResponse.success("任务优先级已由管理员覆盖", task));
    }
}
