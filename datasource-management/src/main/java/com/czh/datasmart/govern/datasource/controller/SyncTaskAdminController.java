package com.czh.datasmart.govern.datasource.controller;

import com.czh.datasmart.govern.datasource.common.ApiResponse;
import com.czh.datasmart.govern.datasource.controller.dto.SyncActionRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncLeaseRecoveryResult;
import com.czh.datasmart.govern.datasource.controller.dto.SyncPriorityOverrideRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncQueueAgingScanResult;
import com.czh.datasmart.govern.datasource.controller.dto.SyncQueueHealthSnapshot;
import com.czh.datasmart.govern.datasource.controller.dto.SyncTimeoutOverrideRequest;
import com.czh.datasmart.govern.datasource.entity.SyncTask;
import com.czh.datasmart.govern.datasource.service.SyncTaskService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @Author : Cui
 * @Date: 2026/4/19 21:01
 * @Description DataSmart Govern Backend - SyncTaskAdminController.java
 * @Version:1.0.0
 *
 * 同步任务管理员控制器。
 * 这类接口与普通任务接口分开，是为了在路由层就把“日常任务动作”和“平台治理动作”区分出来。
 *
 * 当前管理员面主要承载四类能力：
 * 1. 强制控制：强制重试、强制取消、覆盖优先级、覆盖超时。
 * 2. 生命周期治理：归档任务、恢复过期租约。
 * 3. 队列观察：查看当前排队健康快照。
 * 4. 队列巡检：扫描排队老化任务并标记需要人工关注的对象。
 *
 * 这种拆分有几个产品价值：
 * - 权限更容易收敛到 admin-only 路由；
 * - 审计能明确区分“业务用户动作”和“平台治理动作”；
 * - 后续接 permission-admin 模块时，更容易做资源分类和菜单绑定。
 */
@RestController
@RequestMapping("/sync/admin/tasks")
@RequiredArgsConstructor
public class SyncTaskAdminController {

    private final SyncTaskService syncTaskService;

    /**
     * 管理员强制重试。
     */
    @PostMapping("/{id}/force-retry")
    public ResponseEntity<ApiResponse<SyncTask>> forceRetry(
            @PathVariable Long id,
            @Valid @RequestBody SyncActionRequest request) {
        return ResponseEntity.ok(ApiResponse.success("管理员强制重试已生效", syncTaskService.forceRetry(id, request)));
    }

    /**
     * 管理员强制取消。
     */
    @PostMapping("/{id}/force-cancel")
    public ResponseEntity<ApiResponse<SyncTask>> forceCancel(
            @PathVariable Long id,
            @Valid @RequestBody SyncActionRequest request) {
        return ResponseEntity.ok(ApiResponse.success("管理员强制取消已生效", syncTaskService.forceCancel(id, request)));
    }

    /**
     * 管理员覆盖优先级。
     */
    @PostMapping("/{id}/override-priority")
    public ResponseEntity<ApiResponse<SyncTask>> overridePriority(
            @PathVariable Long id,
            @Valid @RequestBody SyncPriorityOverrideRequest request) {
        return ResponseEntity.ok(ApiResponse.success("任务优先级已覆盖", syncTaskService.overridePriority(id, request)));
    }

    /**
     * 管理员覆盖超时。
     */
    @PostMapping("/{id}/override-timeout")
    public ResponseEntity<ApiResponse<SyncTask>> overrideTimeout(
            @PathVariable Long id,
            @Valid @RequestBody SyncTimeoutOverrideRequest request) {
        return ResponseEntity.ok(ApiResponse.success("任务超时配置已覆盖", syncTaskService.overrideTimeout(id, request)));
    }

    /**
     * 管理员归档任务。
     */
    @PostMapping("/{id}/archive")
    public ResponseEntity<ApiResponse<SyncTask>> archive(
            @PathVariable Long id,
            @Valid @RequestBody SyncActionRequest request) {
        return ResponseEntity.ok(ApiResponse.success("任务已归档", syncTaskService.archive(id, request)));
    }

    /**
     * 恢复过期租约。
     */
    @PostMapping("/recover-expired-leases")
    public ResponseEntity<ApiResponse<SyncLeaseRecoveryResult>> recoverExpiredLeases(
            @Valid @RequestBody SyncActionRequest request) {
        return ResponseEntity.ok(ApiResponse.success("过期租约恢复处理完成", syncTaskService.recoverExpiredLeases(request)));
    }

    /**
     * 查看当前队列健康快照。
     * 这里返回的是一个“运营视角”的全局摘要，便于在任务中心、运营看板或告警中心直接消费。
     */
    @PostMapping("/queue-health")
    public ResponseEntity<ApiResponse<SyncQueueHealthSnapshot>> inspectQueueHealth(
            @Valid @RequestBody SyncActionRequest request) {
        return ResponseEntity.ok(ApiResponse.success("队列健康快照获取成功", syncTaskService.inspectQueueHealth(request)));
    }

    /**
     * 执行队列老化巡检。
     * 巡检会找出排队时间过长的任务，并把它们标记为需要人工关注。
     */
    @PostMapping("/scan-queue-aging")
    public ResponseEntity<ApiResponse<SyncQueueAgingScanResult>> scanQueueAging(
            @Valid @RequestBody SyncActionRequest request) {
        return ResponseEntity.ok(ApiResponse.success("队列老化巡检完成", syncTaskService.scanQueuedTaskAging(request)));
    }
}
