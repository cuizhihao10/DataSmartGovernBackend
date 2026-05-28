package com.czh.datasmart.govern.task.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.czh.datasmart.govern.task.common.ApiResponse;
import com.czh.datasmart.govern.task.controller.dto.TaskQueueInspectionRequest;
import com.czh.datasmart.govern.task.controller.dto.TaskQueueItemView;
import com.czh.datasmart.govern.task.controller.dto.TaskQueueSummaryResponse;
import com.czh.datasmart.govern.task.controller.support.TaskActorContextResolver;
import com.czh.datasmart.govern.task.entity.Task;
import com.czh.datasmart.govern.task.service.TaskService;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @Author : Cui
 * @Date: 2026/05/07 21:38
 * @Description DataSmart Govern Backend - TaskQueueOperationsController.java
 * @Version:1.0.0
 *
 * 任务队列运营控制器。
 *
 * <p>该控制器从 TaskController 中拆出，专门承载 `/tasks/operations/queue` 这一类运营视图接口。
 * 这些接口不是普通用户的任务 CRUD，而是面向运营、管理员和调度排障的“队列健康观察面”。
 * 独立后可以继续扩展积压告警、SLA 看板、租户队列公平性、任务老化分析和死信治理，
 * 而不会让普通任务资源控制器继续膨胀。</p>
 */
@RestController
@RequestMapping("/tasks/operations")
@RequiredArgsConstructor
public class TaskQueueOperationsController {

    private final TaskService taskService;
    private final TaskActorContextResolver actorContextResolver;

    /**
     * 查询任务队列运营视图。
     *
     * <p>该接口用于发现“当前队列里有什么值得关注”的任务，例如 PENDING、DEFERRED、DEAD_LETTER、
     * attentionRequired 或排队过久的任务。它返回原始 Task 分页，适合兼容早期联调和基础后台页面。</p>
     */
    @GetMapping("/queue")
    public ResponseEntity<ApiResponse<IPage<Task>>> inspectQueue(
            @Valid TaskQueueInspectionRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.success("任务队列运营视图查询成功",
                taskService.inspectQueue(request,
                        actorContextResolver.resolve(httpRequest))));
    }

    /**
     * 查询任务队列运营项视图。
     *
     * <p>相比原始 Task 列表，该接口会补充 queueAgeSeconds、leaseRemainingSeconds、riskReason、
     * recommendedAction 等更接近运营工作台的解释字段，便于后续直接构建队列治理页面。</p>
     */
    @GetMapping("/queue/items")
    public ResponseEntity<ApiResponse<IPage<TaskQueueItemView>>> inspectQueueItems(
            @Valid TaskQueueInspectionRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.success("任务队列运营项视图查询成功",
                taskService.inspectQueueItems(request,
                        actorContextResolver.resolve(httpRequest))));
    }

    /**
     * 查询任务队列运营汇总。
     *
     * <p>汇总接口面向运营首页、队列大盘和告警前置判断，返回状态分布、关注任务数、
     * 死信任务数、最老排队时间和最大连续退避次数等指标。</p>
     */
    @GetMapping("/queue/summary")
    public ResponseEntity<ApiResponse<TaskQueueSummaryResponse>> summarizeQueue(
            @Valid TaskQueueInspectionRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.success("任务队列运营汇总查询成功",
                taskService.summarizeQueue(request,
                        actorContextResolver.resolve(httpRequest))));
    }
}
