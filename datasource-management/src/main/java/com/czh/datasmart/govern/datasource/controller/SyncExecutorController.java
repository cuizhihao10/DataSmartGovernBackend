package com.czh.datasmart.govern.datasource.controller;

import com.czh.datasmart.govern.datasource.common.ApiResponse;
import com.czh.datasmart.govern.datasource.controller.dto.SyncExecutorClaimRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncExecutorClaimResult;
import com.czh.datasmart.govern.datasource.controller.dto.SyncExecutorHeartbeatRequest;
import com.czh.datasmart.govern.datasource.entity.SyncTask;
import com.czh.datasmart.govern.datasource.service.SyncTaskService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @Author : Cui
 * @Date: 2026/4/19 19:21
 * @Description DataSmart Govern Backend - SyncExecutorController.java
 * @Version:1.0.0
 *
 * 同步执行器控制器。
 * 这一组接口不是给普通用户点按钮用的，而是给未来真实执行器节点、调度代理或工作进程调用的控制面契约。
 *
 * 当前先落两类最基础能力：
 * 1. claim：执行器从队列中认领一个任务。
 * 2. heartbeat：执行器告诉平台“我还活着，请继续保留我的租约”。
 *
 * 后续如果继续产品化，这里还可以继续扩展：
 * - 批量 claim；
 * - worker 注册与下线；
 * - 执行器能力上报；
 * - 失联恢复与租约回收；
 * - 执行器负载统计。
 */
@RestController
@RequestMapping("/sync/executor")
@RequiredArgsConstructor
public class SyncExecutorController {

    private final SyncTaskService syncTaskService;

    /**
     * 执行器认领下一个待执行任务。
     */
    @PostMapping("/claim")
    public ResponseEntity<ApiResponse<SyncExecutorClaimResult>> claim(
            @Valid @RequestBody SyncExecutorClaimRequest request) {
        SyncExecutorClaimResult result = syncTaskService.claimNextQueuedTask(request);
        String message = result == null ? "当前暂无可认领任务" : "执行器认领任务成功";
        return ResponseEntity.ok(ApiResponse.success(message, result));
    }

    /**
     * 执行器心跳续租。
     */
    @PostMapping("/heartbeat")
    public ResponseEntity<ApiResponse<SyncTask>> heartbeat(
            @Valid @RequestBody SyncExecutorHeartbeatRequest request) {
        return ResponseEntity.ok(ApiResponse.success("执行器心跳已续租", syncTaskService.heartbeatExecution(request)));
    }
}
