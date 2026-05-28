package com.czh.datasmart.govern.task.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.task.common.ApiResponse;
import com.czh.datasmart.govern.task.controller.dto.CreateTaskRequest;
import com.czh.datasmart.govern.task.controller.dto.TaskActorContext;
import com.czh.datasmart.govern.task.controller.dto.TaskCompleteRequest;
import com.czh.datasmart.govern.task.controller.dto.TaskDeferRequest;
import com.czh.datasmart.govern.task.controller.dto.TaskExecutionCallbackContext;
import com.czh.datasmart.govern.task.controller.dto.TaskExecutionClaimRequest;
import com.czh.datasmart.govern.task.controller.dto.TaskExecutionClaimResult;
import com.czh.datasmart.govern.task.controller.dto.TaskExecutionHeartbeatRequest;
import com.czh.datasmart.govern.task.controller.dto.TaskFailRequest;
import com.czh.datasmart.govern.task.controller.dto.TaskLeaseRecoveryResult;
import com.czh.datasmart.govern.task.controller.dto.TaskProgressRequest;
import com.czh.datasmart.govern.task.controller.support.TaskActorContextResolver;
import com.czh.datasmart.govern.task.entity.Task;
import com.czh.datasmart.govern.task.entity.TaskExecutionLog;
import com.czh.datasmart.govern.task.entity.TaskExecutionRun;
import com.czh.datasmart.govern.task.service.TaskService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * @Author : Cui
 * @Date: 2026/4/18 22:18
 * @Description DataSmart Govern Backend - TaskController.java
 * @Version:1.0.0
 *
 * 任务基础资源控制器。
 *
 * <p>该控制器只保留任务普通资源入口、执行器协议入口和任务轨迹查询入口。
 * 队列运营接口已经拆到 TaskQueueOperationsController，管理员强控接口已经拆到
 * TaskAdminOperationsController，避免一个 Controller 同时承载 CRUD、运营、强控和执行器协议而持续膨胀。</p>
 *
 * <p>Controller 层的核心职责是维护外部 HTTP 契约：路由、参数、校验、统一响应和平台上下文透传。
 * 真正的状态机、权限、幂等、租约和数据范围判断都下沉到 service/support 层。</p>
 */
@RestController
@RequestMapping("/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;
    private final TaskActorContextResolver actorContextResolver;

    /**
     * 创建任务。
     *
     * <p>创建时请求体可以携带 tenantId、ownerId、projectId，但这些字段不是最终可信来源。
     * 服务层会结合网关透传的 actorContext 做归属解析，避免普通用户伪造租户或负责人。</p>
     */
    @PostMapping
    public ResponseEntity<ApiResponse<Task>> createTask(
            @Valid @RequestBody CreateTaskRequest request,
            HttpServletRequest httpRequest) {
        Task task = taskService.createTask(
                request.getName(),
                request.getDescription(),
                request.getType(),
                request.getParams(),
                request.getPriority(),
                request.getMaxRetryCount(),
                request.getMaxDeferCount(),
                request.getTenantId(),
                request.getOwnerId(),
                request.getProjectId(),
                actorContext(httpRequest));
        return ResponseEntity.ok(ApiResponse.success("任务创建成功", task));
    }

    /**
     * 分页查询任务。
     *
     * <p>请求参数用于缩小结果集，actorContext 用于确定调用者最大可见范围。
     * 也就是说，前端传入的 tenantId/ownerId/projectId 不能扩大权限，只能在服务端允许范围内继续过滤。</p>
     */
    @GetMapping
    public ResponseEntity<ApiResponse<IPage<Task>>> listTasks(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) Long ownerId,
            @RequestParam(required = false) Long projectId,
            HttpServletRequest httpRequest) {
        IPage<Task> page = taskService.listTasks(current, size, status, type, tenantId, ownerId, projectId,
                actorContext(httpRequest));
        return ResponseEntity.ok(ApiResponse.success("任务列表查询成功", page));
    }

    /**
     * 查询任务详情。
     *
     * <p>详情查询同样必须进入服务层做数据范围校验，避免调用方绕过列表接口直接按 taskId 越权读取。</p>
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Task>> getTask(@PathVariable Long id, HttpServletRequest httpRequest) {
        Task task = taskService.getTaskDetail(id, actorContext(httpRequest));
        if (task == null) {
            throw new NoSuchElementException("任务不存在: " + id);
        }
        return ResponseEntity.ok(ApiResponse.success("任务详情查询成功", task));
    }

    /**
     * 启动待执行任务。
     *
     * <p>当前保留 PENDING -> RUNNING 的兼容语义；未来真实 worker 成为主路径后，
     * 用户侧启动动作更适合改成“入队等待认领”。</p>
     */
    @PostMapping("/{id}/start")
    public ResponseEntity<ApiResponse<Task>> startTask(@PathVariable Long id, HttpServletRequest httpRequest) {
        taskService.startTask(id, actorContext(httpRequest));
        return ResponseEntity.ok(ApiResponse.success("任务启动成功", taskService.getTaskDetail(id, actorContext(httpRequest))));
    }

    @PostMapping("/{id}/pause")
    public ResponseEntity<ApiResponse<Task>> pauseTask(@PathVariable Long id, HttpServletRequest httpRequest) {
        taskService.pauseTask(id, actorContext(httpRequest));
        return ResponseEntity.ok(ApiResponse.success("任务暂停成功", taskService.getTaskDetail(id, actorContext(httpRequest))));
    }

    @PostMapping("/{id}/resume")
    public ResponseEntity<ApiResponse<Task>> resumeTask(@PathVariable Long id, HttpServletRequest httpRequest) {
        taskService.resumeTask(id, actorContext(httpRequest));
        return ResponseEntity.ok(ApiResponse.success("任务恢复成功", taskService.getTaskDetail(id, actorContext(httpRequest))));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<ApiResponse<Task>> cancelTask(@PathVariable Long id, HttpServletRequest httpRequest) {
        taskService.cancelTask(id, actorContext(httpRequest));
        return ResponseEntity.ok(ApiResponse.success("任务取消成功", taskService.getTaskDetail(id, actorContext(httpRequest))));
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<ApiResponse<Task>> retryTask(@PathVariable Long id, HttpServletRequest httpRequest) {
        Task task = taskService.retryTask(id, actorContext(httpRequest));
        return ResponseEntity.ok(ApiResponse.success("任务重试成功", task));
    }

    /**
     * 执行器认领下一条任务。
     *
     * <p>该接口是数据库轻量队列协议入口。服务层会基于状态、排队时间、租约和 actorContext
     * 做条件抢占，避免多个 worker 同时拿到同一条任务。</p>
     */
    @PostMapping("/executions/claim")
    public ResponseEntity<ApiResponse<TaskExecutionClaimResult>> claimNextTask(
            @Valid @RequestBody TaskExecutionClaimRequest request,
            HttpServletRequest httpRequest) {
        TaskExecutionClaimResult result = taskService.claimNextTask(request,
                actorContext(httpRequest));
        return ResponseEntity.ok(ApiResponse.success("任务认领请求处理完成", result));
    }

    /**
     * 执行器心跳续租。
     *
     * <p>心跳续租用于证明 worker 仍在处理当前 run，并刷新 checkpoint/progress，避免超时恢复误判。</p>
     */
    @PostMapping("/executions/{runId}/heartbeat")
    public ResponseEntity<ApiResponse<TaskExecutionRun>> heartbeatExecution(
            @PathVariable Long runId,
            @Valid @RequestBody TaskExecutionHeartbeatRequest request,
            HttpServletRequest httpRequest) {
        TaskExecutionRun run = taskService.heartbeatExecution(runId, request,
                actorContext(httpRequest));
        return ResponseEntity.ok(ApiResponse.success("执行器心跳续租成功", run));
    }

    /**
     * 恢复超时租约。
     *
     * <p>该接口当前由人工或脚本触发，未来可由调度器周期扫描调用，并进一步接入告警和恢复指标。</p>
     */
    @PostMapping("/executions/recover-timeout")
    public ResponseEntity<ApiResponse<TaskLeaseRecoveryResult>> recoverTimedOutExecutions(
            @RequestParam(required = false) Integer limit,
            HttpServletRequest httpRequest) {
        TaskLeaseRecoveryResult result = taskService.recoverTimedOutExecutions(limit,
                actorContext(httpRequest));
        return ResponseEntity.ok(ApiResponse.success("租约超时恢复扫描完成", result));
    }

    /**
     * 执行器更新任务进度。
     *
     * <p>runId、executorId、idempotencyKey 是回调安全协议的一部分：
     * runId 防止旧 run 污染新 run，executorId 防止非租约持有人回写，idempotencyKey 防止网络重试重复推进。</p>
     */
    @PostMapping("/{id}/progress")
    public ResponseEntity<ApiResponse<Task>> updateProgress(@PathVariable Long id,
                                                            @Valid @RequestBody TaskProgressRequest request,
                                                            HttpServletRequest httpRequest) {
        taskService.updateProgress(id, request.getProgress(), request.getCheckpoint(),
                callbackContext(request.getRunId(), request.getExecutorId(), request.getIdempotencyKey(), httpRequest));
        return ResponseEntity.ok(ApiResponse.success("任务进度更新成功", taskService.getTaskDetail(id, actorContext(httpRequest))));
    }

    @PostMapping("/{id}/complete")
    public ResponseEntity<ApiResponse<Task>> completeTask(@PathVariable Long id,
                                                          @Valid @RequestBody TaskCompleteRequest request,
                                                          HttpServletRequest httpRequest) {
        taskService.completeTask(id, request.getResult(),
                callbackContext(request.getRunId(), request.getExecutorId(), request.getIdempotencyKey(), httpRequest));
        return ResponseEntity.ok(ApiResponse.success("任务完成成功", taskService.getTaskDetail(id, actorContext(httpRequest))));
    }

    @PostMapping("/{id}/fail")
    public ResponseEntity<ApiResponse<Task>> failTask(@PathVariable Long id,
                                                      @Valid @RequestBody TaskFailRequest request,
                                                      HttpServletRequest httpRequest) {
        taskService.failTask(id, request.getErrorMessage(),
                callbackContext(request.getRunId(), request.getExecutorId(), request.getIdempotencyKey(), httpRequest));
        return ResponseEntity.ok(ApiResponse.success("任务失败状态已记录", taskService.getTaskDetail(id, actorContext(httpRequest))));
    }

    @PostMapping("/{id}/defer")
    public ResponseEntity<ApiResponse<Task>> deferTask(@PathVariable Long id,
                                                       @Valid @RequestBody TaskDeferRequest request,
                                                       HttpServletRequest httpRequest) {
        taskService.deferTask(id, request.getReason(), request.getDelaySeconds(),
                callbackContext(request.getRunId(), request.getExecutorId(), request.getIdempotencyKey(), httpRequest));
        return ResponseEntity.ok(ApiResponse.success("任务已延迟回队列", taskService.getTaskDetail(id, actorContext(httpRequest))));
    }

    @GetMapping("/{id}/logs")
    public ResponseEntity<ApiResponse<List<TaskExecutionLog>>> listExecutionLogs(@PathVariable Long id,
                                                                                 HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.success("任务执行日志查询成功",
                taskService.listExecutionLogs(id, actorContext(httpRequest))));
    }

    @GetMapping("/{id}/runs")
    public ResponseEntity<ApiResponse<List<TaskExecutionRun>>> listExecutionRuns(@PathVariable Long id,
                                                                                 HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.success("任务执行记录查询成功",
                taskService.listExecutionRuns(id, actorContext(httpRequest))));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Boolean>> deleteTask(@PathVariable Long id, HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.success("任务删除成功", taskService.deleteTask(id, actorContext(httpRequest))));
    }

    private TaskActorContext actorContext(HttpServletRequest request) {
        return actorContextResolver.resolve(request);
    }

    private TaskExecutionCallbackContext callbackContext(Long runId,
                                                         String executorId,
                                                         String idempotencyKey,
                                                         HttpServletRequest request) {
        return new TaskExecutionCallbackContext(runId, executorId, idempotencyKey, actorContext(request));
    }
}
