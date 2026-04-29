package com.czh.datasmart.govern.datasource.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.czh.datasmart.govern.datasource.common.ApiResponse;
import com.czh.datasmart.govern.datasource.controller.dto.SyncActionRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncAlertDispatchBatchResult;
import com.czh.datasmart.govern.datasource.controller.dto.SyncAlertOutboxHealthSnapshot;
import com.czh.datasmart.govern.datasource.controller.dto.SyncAlertOutboxLeaseRecoveryResult;
import com.czh.datasmart.govern.datasource.entity.SyncAlertDeliveryRecord;
import com.czh.datasmart.govern.datasource.entity.SyncGovernanceAlert;
import com.czh.datasmart.govern.datasource.service.SyncGovernanceAlertService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * @Author : Cui
 * @Date: 2026/04/25 00:00
 * @Description DataSmart Govern Backend - SyncAlertAdminController.java
 * @Version:1.0.0
 *
 * 同步治理告警管理员控制器。
 *
 * 这里承载的是 datasource-management 领域内的告警治理入口。
 * 它不是最终的统一 observability 告警中心，但已经提供了一个真实产品需要的最小闭环：
 * 1. 查询告警对象，知道当前有哪些风险；
 * 2. 查看投递明细，知道外部通知是否成功、失败在哪个通道；
 * 3. 确认和解决告警，形成运营处理状态；
 * 4. 手动或批量补投告警，处理外部通道短暂故障；
 * 5. 查看 outbox 健康和恢复过期租约，支撑多实例调度下的可靠投递。
 */
@RestController
@RequestMapping("/sync/admin/alerts")
@RequiredArgsConstructor
public class SyncAlertAdminController {

    private final SyncGovernanceAlertService syncGovernanceAlertService;

    /**
     * 分页查询治理告警。
     *
     * 查询参数同时支持租户、告警类型、严重级别、业务状态和投递状态。
     * 这样前端可以构建“全部告警、我的租户告警、死信告警、严重告警”等多个运营视图。
     */
    @GetMapping
    public ResponseEntity<ApiResponse<IPage<SyncGovernanceAlert>>> listAlerts(
            @RequestParam Long actorId,
            @RequestParam String actorRole,
            @RequestParam Long actorTenantId,
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) String alertType,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String alertStatus,
            @RequestParam(required = false) String deliveryStatus) {
        return ResponseEntity.ok(ApiResponse.success(syncGovernanceAlertService.pageAlerts(
                new Page<>(current, size), actorId, actorRole, actorTenantId,
                tenantId, alertType, severity, alertStatus, deliveryStatus)));
    }

    /**
     * 分页查看告警投递明细。
     *
     * 当告警进入 FAILED、SKIPPED 或 DEAD_LETTER 时，运维通常需要继续下钻到“每次投递尝试”这一层，
     * 才能判断到底是通道未配置、HTTP 返回异常、网络超时，还是兜底通道被跳过。
     */
    @GetMapping("/{id}/deliveries")
    public ResponseEntity<ApiResponse<IPage<SyncAlertDeliveryRecord>>> listAlertDeliveryRecords(
            @PathVariable Long id,
            @RequestParam(required = false) Long actorId,
            @RequestParam String actorRole,
            @RequestParam(required = false) Long actorTenantId,
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size) {
        return ResponseEntity.ok(ApiResponse.success("治理告警投递明细查询成功",
                syncGovernanceAlertService.pageDeliveryRecords(id, new Page<>(current, size), actorId, actorRole, actorTenantId)));
    }

    /**
     * 确认告警。
     *
     * 确认表示“已经有人看到并接手”，它不等于问题已经解决。
     * 这个状态能避免多人值班时重复响应同一条风险。
     */
    @PostMapping("/{id}/acknowledge")
    public ResponseEntity<ApiResponse<SyncGovernanceAlert>> acknowledgeAlert(
            @PathVariable Long id,
            @Valid @RequestBody SyncActionRequest request) {
        return ResponseEntity.ok(ApiResponse.success("治理告警已确认",
                syncGovernanceAlertService.acknowledgeAlert(id, request)));
    }

    /**
     * 解决告警。
     *
     * 解决表示风险已经被处理完成，例如队列积压下降、外部通道恢复、死信重新入队并成功投递。
     */
    @PostMapping("/{id}/resolve")
    public ResponseEntity<ApiResponse<SyncGovernanceAlert>> resolveAlert(
            @PathVariable Long id,
            @Valid @RequestBody SyncActionRequest request) {
        return ResponseEntity.ok(ApiResponse.success("治理告警已解决",
                syncGovernanceAlertService.resolveAlert(id, request)));
    }

    /**
     * 手动投递单条告警。
     *
     * 手动投递适合排障后的即时验证，例如修正 webhook 地址后立刻测试某条告警能否成功发送。
     * 服务层会先尝试认领 outbox 租约，避免和后台定时补投并发发送同一条告警。
     */
    @PostMapping("/{id}/dispatch")
    public ResponseEntity<ApiResponse<SyncGovernanceAlert>> dispatchAlert(
            @PathVariable Long id,
            @Valid @RequestBody SyncActionRequest request) {
        return ResponseEntity.ok(ApiResponse.success("治理告警已执行投递动作",
                syncGovernanceAlertService.dispatchAlert(id, request)));
    }

    /**
     * 批量扫描并补投当前到期可重试的告警。
     *
     * 这个入口主要服务于人工补投、故障恢复和后台调度器接入前的治理场景。
     */
    @PostMapping("/dispatch-retryable")
    public ResponseEntity<ApiResponse<SyncAlertDispatchBatchResult>> dispatchRetryableAlerts(
            @Valid @RequestBody SyncActionRequest request) {
        return ResponseEntity.ok(ApiResponse.success("可重试治理告警补投完成",
                syncGovernanceAlertService.dispatchRetryableAlerts(request)));
    }

    /**
     * 查看告警 outbox 健康快照。
     *
     * 这个接口不直接处理任何告警，而是给运营人员一个“投递中心是否健康”的聚合视图：
     * PENDING、FAILED、DEAD_LETTER 说明投递状态，leased 和 expiredLease 说明分布式调度租约状态，
     * oldestDueRetryAt 帮助判断积压已经持续了多久。
     */
    @GetMapping("/outbox-health")
    public ResponseEntity<ApiResponse<SyncAlertOutboxHealthSnapshot>> inspectOutboxHealth(
            @RequestParam(required = false) Long actorId,
            @RequestParam String actorRole,
            @RequestParam(required = false) Long actorTenantId,
            @RequestParam(required = false) Long tenantId) {
        return ResponseEntity.ok(ApiResponse.success("治理告警 outbox 健康快照查询成功",
                syncGovernanceAlertService.inspectOutboxHealth(actorId, actorRole, actorTenantId, tenantId)));
    }

    /**
     * 恢复已经过期的 outbox 投递租约。
     *
     * 当某个服务实例认领告警后宕机，dispatch_lease_owner 可能残留在数据库中。
     * 这个接口会释放已经过期的租约，让后续调度器或人工补投重新认领这些告警，避免 outbox 卡死。
     */
    @PostMapping("/recover-expired-leases")
    public ResponseEntity<ApiResponse<SyncAlertOutboxLeaseRecoveryResult>> recoverExpiredOutboxLeases(
            @RequestParam(defaultValue = "100") Integer batchSize,
            @Valid @RequestBody SyncActionRequest request) {
        return ResponseEntity.ok(ApiResponse.success("治理告警 outbox 过期租约恢复完成",
                syncGovernanceAlertService.recoverExpiredDispatchLeases(request, batchSize)));
    }

    /**
     * 将死信告警重新放回可投递状态。
     *
     * 适合在外部告警平台恢复、通道配置修正或临时网络故障解除后使用。
     */
    @PostMapping("/{id}/requeue")
    public ResponseEntity<ApiResponse<SyncGovernanceAlert>> requeueDeadLetterAlert(
            @PathVariable Long id,
            @Valid @RequestBody SyncActionRequest request) {
        return ResponseEntity.ok(ApiResponse.success("死信治理告警已重新入队",
                syncGovernanceAlertService.requeueDeadLetterAlert(id, request)));
    }
}
