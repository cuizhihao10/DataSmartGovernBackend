package com.czh.datasmart.govern.task.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.czh.datasmart.govern.task.common.ApiResponse;
import com.czh.datasmart.govern.task.controller.dto.CreateTaskRequest;
import com.czh.datasmart.govern.task.controller.dto.TaskCompleteRequest;
import com.czh.datasmart.govern.task.controller.dto.TaskFailRequest;
import com.czh.datasmart.govern.task.controller.dto.TaskProgressRequest;
import com.czh.datasmart.govern.task.entity.Task;
import com.czh.datasmart.govern.task.entity.TaskExecutionLog;
import com.czh.datasmart.govern.task.service.TaskService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * 任务管理控制器。
 * <p>
 * 在典型的 Spring 分层中，Controller 负责的是“接口契约”和“请求入口管理”，
 * 而不是深度业务判断。也就是说，这一层更关注：
 * 1. URL 怎么定义。
 * 2. 请求参数怎么校验。
 * 3. 返回值如何统一。
 * 4. 哪个业务动作应该转交给 Service。
 * <p>
 * 这样设计的好处是，接口协议和业务规则可以分别演进：
 * - 想调整任务状态机，就主要改 Service。
 * - 想调整 HTTP 交互方式，就主要改 Controller。
 */
@RestController
@RequestMapping("/tasks")
@RequiredArgsConstructor
public class TaskController {

    /**
     * 控制器只依赖业务服务接口，不直接碰数据库层。
     * 这能帮助我们维持清晰边界，避免后面接口层越写越“胖”。
     */
    private final TaskService taskService;

    /**
     * 创建任务。
     * <p>
     * 这是任务生命周期的入口。当前阶段的任务模块先把“任务登记”这件事做扎实：
     * 保存主记录、初始化状态、记录执行日志，为后续接调度器和智能体执行流打基础。
     */
    @PostMapping
    public ResponseEntity<ApiResponse<Task>> createTask(@Valid @RequestBody CreateTaskRequest request) {
        Task task = taskService.createTask(
                request.getName(),
                request.getDescription(),
                request.getType(),
                request.getParams(),
                request.getPriority(),
                request.getMaxRetryCount()
        );
        return ResponseEntity.ok(ApiResponse.success("task created", task));
    }

    /**
     * 分页查询任务。
     * <p>
     * 这里用的是 MyBatis-Plus 的常见组合：
     * - LambdaQueryWrapper：用类型安全的方式拼查询条件。
     * - Page：封装分页参数。
     * - page(...)：让框架自动生成分页 SQL。
     * <p>
     * 之所以按 createTime 倒序，是因为任务中心最常见的查看方式就是先看最新任务。
     */
    @GetMapping
    public ResponseEntity<ApiResponse<IPage<Task>>> listTasks(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type) {

        LambdaQueryWrapper<Task> wrapper = new LambdaQueryWrapper<>();
        if (status != null) {
            wrapper.eq(Task::getStatus, status);
        }
        if (type != null) {
            wrapper.eq(Task::getType, type);
        }
        wrapper.orderByDesc(Task::getCreateTime);

        Page<Task> page = new Page<>(current, size);
        IPage<Task> result = taskService.page(page, wrapper);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /**
     * 查询任务详情。
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Task>> getTask(@PathVariable Long id) {
        Task task = taskService.getById(id);
        if (task == null) {
            throw new NoSuchElementException("Task not found: " + id);
        }
        return ResponseEntity.ok(ApiResponse.success(task));
    }

    /**
     * 启动任务。
     * <p>
     * 从接口角度看，这是一个显式动作接口，而不是单纯的 update。
     * 用 `/start` 这种动作型路径，能更直观地表达业务语义，也更方便学习和调试。
     */
    @PostMapping("/{id}/start")
    public ResponseEntity<ApiResponse<Task>> startTask(@PathVariable Long id) {
        taskService.startTask(id);
        return ResponseEntity.ok(ApiResponse.success("task started", taskService.getById(id)));
    }

    /**
     * 暂停任务。
     * <p>
     * 暂停能力对于长任务非常重要，因为真实的数据治理流程经常会遇到人工确认、
     * 上游资源不足、依赖任务未就绪等情况。
     */
    @PostMapping("/{id}/pause")
    public ResponseEntity<ApiResponse<Task>> pauseTask(@PathVariable Long id) {
        taskService.pauseTask(id);
        return ResponseEntity.ok(ApiResponse.success("task paused", taskService.getById(id)));
    }

    /**
     * 恢复任务。
     */
    @PostMapping("/{id}/resume")
    public ResponseEntity<ApiResponse<Task>> resumeTask(@PathVariable Long id) {
        taskService.resumeTask(id);
        return ResponseEntity.ok(ApiResponse.success("task resumed", taskService.getById(id)));
    }

    /**
     * 取消任务。
     * <p>
     * 与 fail 不同，cancel 更偏向主动终止动作，因此单独保留一个动作接口更清晰。
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<ApiResponse<Task>> cancelTask(@PathVariable Long id) {
        taskService.cancelTask(id);
        return ResponseEntity.ok(ApiResponse.success("task cancelled", taskService.getById(id)));
    }

    /**
     * 重试任务。
     * <p>
     * 这里返回重试后的 Task 快照，让调用方能直接看到 retryCount、status 等字段的新状态。
     */
    @PostMapping("/{id}/retry")
    public ResponseEntity<ApiResponse<Task>> retryTask(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("task retried", taskService.retryTask(id)));
    }

    /**
     * 更新任务进度。
     * <p>
     * 这个接口未来主要会被执行器、调度器或异步消费者回调。
     * 当前先把契约固定下来，后续接 Kafka 或智能体运行时就可以直接复用。
     */
    @PutMapping("/{id}/progress")
    public ResponseEntity<ApiResponse<Task>> updateProgress(@PathVariable Long id,
                                                            @Valid @RequestBody TaskProgressRequest request) {
        taskService.updateProgress(id, request.getProgress(), request.getCheckpoint());
        return ResponseEntity.ok(ApiResponse.success("task progress updated", taskService.getById(id)));
    }

    /**
     * 标记任务完成。
     */
    @PostMapping("/{id}/complete")
    public ResponseEntity<ApiResponse<Task>> completeTask(@PathVariable Long id,
                                                          @Valid @RequestBody TaskCompleteRequest request) {
        taskService.completeTask(id, request.getResult());
        return ResponseEntity.ok(ApiResponse.success("task completed", taskService.getById(id)));
    }

    /**
     * 标记任务失败。
     * <p>
     * 当前把失败也设计成显式接口，是为了让业务终态表达得更直接，
     * 后续真正执行器接入时也会更容易调用。
     */
    @PostMapping("/{id}/fail")
    public ResponseEntity<ApiResponse<Task>> failTask(@PathVariable Long id,
                                                      @Valid @RequestBody TaskFailRequest request) {
        taskService.failTask(id, request.getErrorMessage());
        return ResponseEntity.ok(ApiResponse.success("task marked as failed", taskService.getById(id)));
    }

    /**
     * 查询任务执行日志。
     * <p>
     * 学习这个模块时，建议把这个接口和 task 详情接口对照着看：
     * - 详情接口看“现在是什么样”。
     * - 日志接口看“为什么变成现在这样”。
     */
    @GetMapping("/{id}/logs")
    public ResponseEntity<ApiResponse<List<TaskExecutionLog>>> listExecutionLogs(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(taskService.listExecutionLogs(id)));
    }

    /**
     * 删除任务。
     * <p>
     * 当前阶段先用物理删除满足最基础管理需求。
     * 如果未来要做审计保留、回收站或软删除，再在这里和 Service 层一起升级。
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Boolean>> deleteTask(@PathVariable Long id) {
        boolean success = taskService.removeById(id);
        if (!success) {
            throw new NoSuchElementException("Task not found: " + id);
        }
        return ResponseEntity.ok(ApiResponse.success("task deleted", true));
    }
}
