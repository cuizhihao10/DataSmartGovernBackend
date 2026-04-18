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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
                request.getMaxRetryCount()
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
     * 查询任务执行日志。
     * 详情接口看“当前状态”，日志接口看“状态变化过程”，二者组合才能完整理解一条任务。
     */
    @GetMapping("/{id}/logs")
    public ResponseEntity<ApiResponse<List<TaskExecutionLog>>> listExecutionLogs(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(taskService.listExecutionLogs(id)));
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
}
