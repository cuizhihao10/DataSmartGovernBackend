package com.czh.datasmart.govern.task.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.task.common.ApiResponse;
import com.czh.datasmart.govern.task.controller.dto.CreateTaskRequest;
import com.czh.datasmart.govern.task.controller.dto.TaskActorContext;
import com.czh.datasmart.govern.task.controller.dto.TaskAdminActionRequest;
import com.czh.datasmart.govern.task.controller.dto.TaskCompleteRequest;
import com.czh.datasmart.govern.task.controller.dto.TaskDeferRequest;
import com.czh.datasmart.govern.task.controller.dto.TaskExecutionClaimRequest;
import com.czh.datasmart.govern.task.controller.dto.TaskExecutionClaimResult;
import com.czh.datasmart.govern.task.controller.dto.TaskExecutionHeartbeatRequest;
import com.czh.datasmart.govern.task.controller.dto.TaskFailRequest;
import com.czh.datasmart.govern.task.controller.dto.TaskLeaseRecoveryResult;
import com.czh.datasmart.govern.task.controller.dto.TaskPriorityOverrideRequest;
import com.czh.datasmart.govern.task.controller.dto.TaskProgressRequest;
import com.czh.datasmart.govern.task.controller.dto.TaskQueueInspectionRequest;
import com.czh.datasmart.govern.task.controller.dto.TaskQueueItemView;
import com.czh.datasmart.govern.task.controller.dto.TaskQueueSummaryResponse;
import com.czh.datasmart.govern.task.entity.Task;
import com.czh.datasmart.govern.task.entity.TaskExecutionLog;
import com.czh.datasmart.govern.task.entity.TaskExecutionRun;
import com.czh.datasmart.govern.task.service.TaskService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
 * 任务管理控制器。
 * Controller 层最重要的职责不是承载复杂业务判断，而是负责“对外接口契约”：
 * 1. 路由路径如何设计。
 * 2. 输入参数如何校验。
 * 3. 哪个 HTTP 动作对应哪个业务动作。
 * 4. 返回结构如何统一。
 *
 * 当前任务模块采用“资源路径 + 动作子路径”的风格，例如：
 * - POST /tasks：创建任务。
 * - POST /tasks/{id}/start：启动任务。
 * - POST /tasks/{id}/retry：重试任务。
 *
 * 这种设计比把所有动作都塞进一个 update 接口更直观，
 * 特别适合作为学习型项目，让人一眼看懂任务生命周期有哪些显式动作。
 */
@RestController
@RequestMapping("/tasks")
@RequiredArgsConstructor
public class TaskController {

    /**
     * 控制器只依赖服务接口，不直接操作 Mapper。
     * 这样能保持“接口层”和“业务层”的边界清晰。
     */
    private final TaskService taskService;

    /**
     * 创建任务。
     * 当前动作会登记任务主记录、初始化状态，并由服务层补写第一条执行日志。
     */
    @PostMapping
    public ResponseEntity<ApiResponse<Task>> createTask(@Valid @RequestBody CreateTaskRequest request) {
        Task task = taskService.createTask(
                request.getName(),
                request.getDescription(),
                request.getType(),
                request.getParams(),
                request.getPriority(),
                request.getMaxRetryCount(),
                request.getMaxDeferCount()
        );
        return ResponseEntity.ok(ApiResponse.success("任务创建成功", task));
    }

    /**
     * 分页查询任务。
     * 当前支持按状态和类型做基础过滤，便于后续任务中心页面快速搭建列表视图。
     * 使用 MyBatis-Plus 的分页能力，可以减少样板 SQL。
     */
    @GetMapping
    public ResponseEntity<ApiResponse<IPage<Task>>> listTasks(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type) {

        LambdaQueryWrapper<Task> wrapper = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) {
            wrapper.eq(Task::getStatus, status);
        }
        if (type != null && !type.isBlank()) {
            wrapper.eq(Task::getType, type);
        }
        wrapper.orderByDesc(Task::getCreateTime);

        Page<Task> page = new Page<>(current, size);
        IPage<Task> result = taskService.page(page, wrapper);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /**
     * 查询任务队列运营视图。
     *
     * <p>这个接口服务的是“运营人员怎么看队列健康”，不是普通业务列表。
     * 因此它会重点支持：
     * - DEFERRED：被执行器主动退避、稍后重新认领的任务；
     * - DEAD_LETTER：连续退避超过上限、已经停止自动调度的任务；
     * - attentionRequired：需要人工关注的失败、超时、死信或其他异常任务；
     * - queuedOlderThanSeconds：排队超过指定时长的任务。
     *
     * <p>路由放在 `/operations/queue` 下，是为了避免和 `/tasks/{id}` 冲突，
     * 也让调用者一眼看出这是运维查询接口，不是普通资源详情接口。
     */
    @GetMapping("/operations/queue")
    public ResponseEntity<ApiResponse<IPage<Task>>> inspectQueue(@Valid TaskQueueInspectionRequest request) {
        return ResponseEntity.ok(ApiResponse.success("任务队列运营视图查询成功", taskService.inspectQueue(request)));
    }

    /**
     * 查询任务队列运营项视图。
     *
     * <p>相比 `/operations/queue` 返回原始 Task，该接口会补充可直接展示的运营解释字段：
     * - queueAgeSeconds：已排队多久；
     * - queuedDelayRemainingSeconds：DEFERRED 任务距离重新可认领还有多久；
     * - leaseRemainingSeconds：RUNNING 任务租约是否接近过期或已经过期；
     * - riskReason：为什么需要关注；
     * - recommendedAction：建议运营人员下一步做什么。
     *
     * <p>保留原始列表接口，是为了兼容已有联调；新增 items 接口，则服务未来真正的运营工作台。
     */
    @GetMapping("/operations/queue/items")
    public ResponseEntity<ApiResponse<IPage<TaskQueueItemView>>> inspectQueueItems(@Valid TaskQueueInspectionRequest request) {
        return ResponseEntity.ok(ApiResponse.success("任务队列运营项视图查询成功", taskService.inspectQueueItems(request)));
    }

    /**
     * 查询任务队列运营汇总。
     *
     * <p>列表接口适合排查具体任务；汇总接口适合运营首页、队列大盘和告警前置判断。
     * 它会返回状态分布、关注任务数、死信任务数、最老排队时间、最大连续退避次数等指标。
     *
     * <p>该接口与 `/operations/queue` 使用相同过滤条件，因此前端可以先调用 summary 看整体健康，
     * 再用同一组查询参数调用 list 查看具体任务。
     */
    @GetMapping("/operations/queue/summary")
    public ResponseEntity<ApiResponse<TaskQueueSummaryResponse>> summarizeQueue(@Valid TaskQueueInspectionRequest request) {
        return ResponseEntity.ok(ApiResponse.success("任务队列运营汇总查询成功", taskService.summarizeQueue(request)));
    }

    /**
     * 查询任务详情。
     * 这个接口主要用于查看任务主表中的当前快照。
     * 如果需要追踪变化过程，应结合日志接口一起看。
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Task>> getTask(@PathVariable Long id) {
        Task task = taskService.getById(id);
        if (task == null) {
            throw new NoSuchElementException("任务不存在: " + id);
        }
        return ResponseEntity.ok(ApiResponse.success(task));
    }

    /**
     * 启动任务。
     * 这是一个显式动作接口，语义上比“更新状态字段”更清楚。
     */
    @PostMapping("/{id}/start")
    public ResponseEntity<ApiResponse<Task>> startTask(@PathVariable Long id) {
        taskService.startTask(id);
        return ResponseEntity.ok(ApiResponse.success("任务已启动", taskService.getById(id)));
    }

    /**
     * 暂停任务。
     * 长任务场景中，暂停是非常常见的管理动作，因此单独保留动作接口。
     */
    @PostMapping("/{id}/pause")
    public ResponseEntity<ApiResponse<Task>> pauseTask(@PathVariable Long id) {
        taskService.pauseTask(id);
        return ResponseEntity.ok(ApiResponse.success("任务已暂停", taskService.getById(id)));
    }

    /**
     * 恢复任务。
     * 用于把已暂停任务重新推回运行态。
     */
    @PostMapping("/{id}/resume")
    public ResponseEntity<ApiResponse<Task>> resumeTask(@PathVariable Long id) {
        taskService.resumeTask(id);
        return ResponseEntity.ok(ApiResponse.success("任务已恢复", taskService.getById(id)));
    }

    /**
     * 取消任务。
     * 取消表达的是“主动终止”，和失败这种“被动异常终止”是两种不同业务语义。
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<ApiResponse<Task>> cancelTask(@PathVariable Long id) {
        taskService.cancelTask(id);
        return ResponseEntity.ok(ApiResponse.success("任务已取消", taskService.getById(id)));
    }

    /**
     * 重试任务。
     * 返回重试后的任务快照，便于调用方立刻看到 retryCount、status 等字段的新状态。
     */
    @PostMapping("/{id}/retry")
    public ResponseEntity<ApiResponse<Task>> retryTask(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("任务已重试", taskService.retryTask(id)));
    }

    /**
     * 执行器认领下一条可执行任务。
     *
     * <p>这是任务中心从“人工启动任务”走向“调度器/执行器拉取任务”的关键接口。
     * 执行器提交 executorId、可选 taskType 和租约秒数，任务中心按优先级和创建时间返回一条 PENDING 任务。
     *
     * <p>权限说明：
     * 当前服务层允许 SERVICE_ACCOUNT、OPERATOR、PLATFORM_ADMINISTRATOR 调用。
     * 未来生产环境中，常规执行器应使用 SERVICE_ACCOUNT 身份，并由 gateway 或服务间鉴权保证调用来源可信。
     */
    @PostMapping("/executions/claim")
    public ResponseEntity<ApiResponse<TaskExecutionClaimResult>> claimNextTask(
            @Valid @RequestBody TaskExecutionClaimRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        TaskExecutionClaimResult result = taskService.claimNextTask(request,
                actorContext(actorTenantId, actorId, actorRole, traceId));
        return ResponseEntity.ok(ApiResponse.success(result.claimed() ? "任务认领成功" : "暂无可认领任务", result));
    }

    /**
     * 执行器心跳续租。
     *
     * <p>执行器在执行长任务期间应周期性调用该接口。
     * 心跳会刷新任务主表的进度、checkpoint、heartbeatTime 和 leaseExpireTime，
     * 同时更新 task_execution_run，使任务详情和执行历史都能看到最新执行状态。
     */
    @PostMapping("/executions/{runId}/heartbeat")
    public ResponseEntity<ApiResponse<TaskExecutionRun>> heartbeatExecution(
            @PathVariable Long runId,
            @Valid @RequestBody TaskExecutionHeartbeatRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        TaskExecutionRun run = taskService.heartbeatExecution(runId, request,
                actorContext(actorTenantId, actorId, actorRole, traceId));
        return ResponseEntity.ok(ApiResponse.success("执行器心跳已续租", run));
    }

    /**
     * 恢复租约超时的执行记录。
     *
     * <p>当前实现为手动触发，适合本地学习、联调和早期运维。
     * 后续可以把这段逻辑挂到调度器或定时任务上，并接入告警和指标。
     */
    @PostMapping("/executions/recover-timeout")
    public ResponseEntity<ApiResponse<TaskLeaseRecoveryResult>> recoverTimedOutExecutions(
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        TaskLeaseRecoveryResult result = taskService.recoverTimedOutExecutions(limit,
                actorContext(actorTenantId, actorId, actorRole, traceId));
        return ResponseEntity.ok(ApiResponse.success("租约超时恢复扫描完成", result));
    }

    /**
     * 管理员强制暂停任务。
     *
     * <p>与普通 `/pause` 的区别：
     * 普通暂停面向标准生命周期，只允许 RUNNING -> PAUSED；
     * 管理员强制暂停面向生产运维，可以把 PENDING 任务也暂停，防止它继续进入调度。
     *
     * <p>权限说明：
     * gateway 负责第一层路由授权；Service 层还会基于 `X-DataSmart-Actor-Role` 做二次校验。
     * 当前只允许 OPERATOR、TENANT_ADMINISTRATOR、PLATFORM_ADMINISTRATOR。
     */
    @PostMapping("/{id}/admin/pause")
    public ResponseEntity<ApiResponse<Task>> forcePauseTask(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) TaskAdminActionRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        Task task = taskService.forcePauseTask(id, request == null ? null : request.getReason(),
                actorContext(actorTenantId, actorId, actorRole, traceId));
        return ResponseEntity.ok(ApiResponse.success("任务已由管理员强制暂停", task));
    }

    /**
     * 管理员恢复任务。
     *
     * <p>当前恢复会把任务从 PAUSED 放回 PENDING，而不是直接改成 RUNNING。
     * 这是为了保证 RUNNING 只代表执行器真正接手，避免管理接口伪造执行态。
     */
    @PostMapping("/{id}/admin/resume")
    public ResponseEntity<ApiResponse<Task>> forceResumeTask(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) TaskAdminActionRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        Task task = taskService.forceResumeTask(id, request == null ? null : request.getReason(),
                actorContext(actorTenantId, actorId, actorRole, traceId));
        return ResponseEntity.ok(ApiResponse.success("任务已由管理员恢复到待调度状态", task));
    }

    /**
     * 管理员强制取消任务。
     *
     * <p>该接口用于事故止损和人工处置，例如下游系统故障、客户撤销需求、任务长时间卡住等场景。
     * 取消原因会写入执行日志 details，便于审计和故障复盘。
     */
    @PostMapping("/{id}/admin/cancel")
    public ResponseEntity<ApiResponse<Task>> forceCancelTask(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) TaskAdminActionRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        Task task = taskService.forceCancelTask(id, request == null ? null : request.getReason(),
                actorContext(actorTenantId, actorId, actorRole, traceId));
        return ResponseEntity.ok(ApiResponse.success("任务已由管理员强制取消", task));
    }

    /**
     * 管理员强制重试任务。
     *
     * <p>该接口覆盖生产恢复场景：
     * 当外部依赖修复、执行器恢复、数据权限修复后，管理员可以把失败/取消/暂停任务重新放回待调度队列。
     * 如确需超过 maxRetryCount，可传入 ignoreRetryLimit=true，并在 reason 中说明原因。
     */
    @PostMapping("/{id}/admin/retry")
    public ResponseEntity<ApiResponse<Task>> forceRetryTask(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) TaskAdminActionRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        Task task = taskService.forceRetryTask(id,
                request == null ? null : request.getReason(),
                request == null ? null : request.getIgnoreRetryLimit(),
                actorContext(actorTenantId, actorId, actorRole, traceId));
        return ResponseEntity.ok(ApiResponse.success("任务已由管理员强制重试", task));
    }

    /**
     * 管理员覆盖任务优先级。
     *
     * <p>这是调度控制能力的前置接口。
     * 当前只更新任务快照和日志；未来接入真实队列后，这里还应该触发队列重排、租户公平性检查和 SLA 风险告警。
     */
    @PutMapping("/{id}/admin/priority")
    public ResponseEntity<ApiResponse<Task>> overridePriority(
            @PathVariable Long id,
            @Valid @RequestBody TaskPriorityOverrideRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        Task task = taskService.overridePriority(id, request.getPriority(), request.getReason(),
                actorContext(actorTenantId, actorId, actorRole, traceId));
        return ResponseEntity.ok(ApiResponse.success("任务优先级已由管理员覆盖", task));
    }

    /**
     * 更新任务进度。
     * 未来这类接口通常会被执行器、调度器或异步消费者调用，因此尽早固定契约很重要。
     */
    @PutMapping("/{id}/progress")
    public ResponseEntity<ApiResponse<Task>> updateProgress(@PathVariable Long id,
                                                            @Valid @RequestBody TaskProgressRequest request) {
        taskService.updateProgress(id, request.getProgress(), request.getCheckpoint());
        return ResponseEntity.ok(ApiResponse.success("任务进度已更新", taskService.getById(id)));
    }

    /**
     * 标记任务完成。
     * 该动作通常由真正的执行逻辑在成功收尾后回调。
     */
    @PostMapping("/{id}/complete")
    public ResponseEntity<ApiResponse<Task>> completeTask(@PathVariable Long id,
                                                          @Valid @RequestBody TaskCompleteRequest request) {
        taskService.completeTask(id, request.getResult());
        return ResponseEntity.ok(ApiResponse.success("任务已完成", taskService.getById(id)));
    }

    /**
     * 标记任务失败。
     * 当前保留显式失败接口，有利于后续执行器统一上报异常结果。
     */
    @PostMapping("/{id}/fail")
    public ResponseEntity<ApiResponse<Task>> failTask(@PathVariable Long id,
                                                      @Valid @RequestBody TaskFailRequest request) {
        taskService.failTask(id, request.getErrorMessage());
        return ResponseEntity.ok(ApiResponse.success("任务已标记失败", taskService.getById(id)));
    }

    /**
     * 执行器主动延迟任务并放回队列。
     *
     * <p>该接口用于生产调度里的背压场景：执行器已经认领任务，但发现当前资源不足，不适合继续执行。
     * 例如 data-quality 本实例并发已满、单租户配额已满、单数据源并发已满、下游服务 429 限流等。
     *
     * <p>路由语义说明：
     * - `POST /tasks/{id}/fail` 表示任务业务执行失败；
     * - `POST /tasks/{id}/defer` 表示执行器主动退避，任务稍后重新进入可认领范围；
     * - 两者都要求任务当前是 RUNNING，但会进入完全不同的状态和运营含义。
     */
    @PostMapping("/{id}/defer")
    public ResponseEntity<ApiResponse<Task>> deferTask(@PathVariable Long id,
                                                       @Valid @RequestBody TaskDeferRequest request) {
        taskService.deferTask(id, request.getReason(), request.getDelaySeconds());
        return ResponseEntity.ok(ApiResponse.success("任务已延迟回队列", taskService.getById(id)));
    }

    /**
     * 查询任务执行日志。
     * 详情接口看“当前状态”，日志接口看“状态变化过程”，二者组合才能完整理解一条任务。
     */
    @GetMapping("/{id}/logs")
    public ResponseEntity<ApiResponse<List<TaskExecutionLog>>> listExecutionLogs(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(taskService.listExecutionLogs(id)));
    }

    /**
     * 查询任务执行记录。
     *
     * <p>日志接口回答“发生过什么事件”，执行记录接口回答“任务跑过几次、每次由哪个执行器执行、执行结果如何”。
     * 两者结合才是完整的任务时间线。
     */
    @GetMapping("/{id}/runs")
    public ResponseEntity<ApiResponse<List<TaskExecutionRun>>> listExecutionRuns(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(taskService.listExecutionRuns(id)));
    }

    /**
     * 删除任务。
     * 当前先采用物理删除满足基础管理需求；
     * 如果后续要引入回收站、审计保留或逻辑删除，可以在服务层和表结构上进一步升级。
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Boolean>> deleteTask(@PathVariable Long id) {
        boolean success = taskService.removeById(id);
        if (!success) {
            throw new NoSuchElementException("任务不存在: " + id);
        }
        return ResponseEntity.ok(ApiResponse.success("任务已删除", true));
    }

    /**
     * 从平台 Header 构建任务操作者上下文。
     *
     * <p>这些 Header 应由 gateway 在认证后写入。
     * task-management 不直接解析登录态，而是使用统一平台上下文，让后续微服务间调用、审计和链路追踪保持一致。
     */
    private TaskActorContext actorContext(Long tenantId, Long actorId, String actorRole, String traceId) {
        return new TaskActorContext(tenantId, actorId, actorRole, traceId);
    }
}
