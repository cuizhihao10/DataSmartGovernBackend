/**
 * @Author : Cui
 * @Date: 2026/05/05 18:43
 * @Description DataSmart Govern Backend - SyncQueueAlertSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.support;

import com.czh.datasmart.govern.datasource.controller.dto.SyncActionRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncQueueHealthSnapshot;
import com.czh.datasmart.govern.datasource.entity.SyncGovernanceAlert;
import com.czh.datasmart.govern.datasource.entity.SyncTask;
import com.czh.datasmart.govern.datasource.service.SyncGovernanceAlertService;
import com.czh.datasmart.govern.datasource.support.SyncAlertSeverity;
import com.czh.datasmart.govern.datasource.support.SyncAlertType;
import com.czh.datasmart.govern.datasource.support.SyncAuditAction;
import com.czh.datasmart.govern.datasource.support.SyncPermissionResource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 同步队列治理告警编排组件。
 *
 * <p>这个组件处理“队列风险如何沉淀成治理告警”的问题。
 * 它不负责查询队列、不负责修改任务、不负责决定队列压力等级；
 * 这些职责分别留在 `SyncTaskServiceImpl` 和 `SyncQueuePolicySupport` 中。
 *
 * <p>拆出该组件的原因是告警逻辑天然会持续增长：
 * 1. 当前只有全局队列压力、租户队列压力、排队老化；
 * 2. 后续可能增加执行器池不可用、租户配额耗尽、连接器失败率升高、CDC 延迟扩大；
 * 3. 商业化产品还可能要把告警推送到 observability、工单、IM、邮件或 Webhook。
 *
 * <p>如果这些逻辑继续堆在任务服务里，任务服务会同时理解状态机、容量、告警文案、通知路由，
 * 很快重新变成难以维护的“上帝类”。因此这里把告警编排提前独立出来。
 */
@Component
@RequiredArgsConstructor
public class SyncQueueAlertSupport {

    /**
     * 同步治理告警服务。
     *
     * <p>当前仍是 datasource-management 内部告警服务，负责打开或刷新领域告警。
     * 未来 observability 模块成熟后，这里可以改为发布领域事件，再由统一告警中心消费。
     */
    private final SyncGovernanceAlertService syncGovernanceAlertService;

    /**
     * 队列策略支持组件。
     *
     * <p>任务级老化告警需要复用“排队秒数计算”，保持健康快照、老化巡检和告警详情口径一致。
     */
    private final SyncQueuePolicySupport syncQueuePolicySupport;

    /**
     * 根据队列健康快照打开或刷新治理告警。
     *
     * <p>队列健康巡检不应该只返回一个瞬时快照。
     * 更接近商用治理平台的做法，是把“已经发现的风险”沉淀为可追踪的治理告警对象，
     * 这样后续才能进行确认、派单、外送和闭环处理。
     *
     * <p>当前会生成三类告警：
     * 1. 全局队列压力：平台整体吞吐或执行器容量可能不足；
     * 2. 租户队列压力：某个租户出现异常洪峰或配额策略不足；
     * 3. 全局老化队列：存在长时间无人认领或执行器无法处理的任务。
     */
    public void openQueueHealthAlerts(SyncQueueHealthSnapshot snapshot,
                                      Long actorId,
                                      String actorRole,
                                      Long highestBacklogTenantId) {
        if (snapshot == null || !Boolean.TRUE.equals(snapshot.getAttentionRequired())) {
            return;
        }

        SyncAlertSeverity severity = "SATURATED".equals(snapshot.getPressureLevel())
                ? SyncAlertSeverity.CRITICAL
                : SyncAlertSeverity.WARNING;

        if (snapshot.getGlobalQueuedCount() != null
                && snapshot.getQueueAlertThresholdGlobal() != null
                && snapshot.getQueueAlertThresholdGlobal() > 0
                && snapshot.getGlobalQueuedCount() >= snapshot.getQueueAlertThresholdGlobal()) {
            syncGovernanceAlertService.openOrRefreshAlert(
                    null,
                    snapshot.getOldestQueuedTaskId(),
                    SyncAlertType.QUEUE_PRESSURE.name(),
                    severity.name(),
                    "QUEUE_PRESSURE:GLOBAL",
                    "全局同步队列进入压力区间",
                    "当前全局待执行任务数=" + snapshot.getGlobalQueuedCount()
                            + "，预警阈值=" + snapshot.getQueueAlertThresholdGlobal()
                            + "，压力等级=" + snapshot.getPressureLevel()
                            + "，建议=" + snapshot.getRecommendation(),
                    SyncPermissionResource.SYNC_QUEUE.name(),
                    SyncAuditAction.INSPECT_QUEUE_HEALTH.name(),
                    actorId,
                    actorRole
            );
        }

        if (highestBacklogTenantId != null
                && snapshot.getHighestBacklogTenantQueuedCount() != null
                && snapshot.getQueueAlertThresholdPerTenant() != null
                && snapshot.getQueueAlertThresholdPerTenant() > 0
                && snapshot.getHighestBacklogTenantQueuedCount() >= snapshot.getQueueAlertThresholdPerTenant()) {
            syncGovernanceAlertService.openOrRefreshAlert(
                    highestBacklogTenantId,
                    snapshot.getOldestQueuedTaskId(),
                    SyncAlertType.QUEUE_PRESSURE.name(),
                    severity.name(),
                    "QUEUE_PRESSURE:TENANT:" + highestBacklogTenantId,
                    "租户同步队列积压偏高",
                    "tenantId=" + highestBacklogTenantId
                            + " 的待执行任务数=" + snapshot.getHighestBacklogTenantQueuedCount()
                            + "，租户预警阈值=" + snapshot.getQueueAlertThresholdPerTenant()
                            + "，建议=" + snapshot.getRecommendation(),
                    SyncPermissionResource.SYNC_QUEUE.name(),
                    SyncAuditAction.INSPECT_QUEUE_HEALTH.name(),
                    actorId,
                    actorRole
            );
        }

        if (snapshot.getAgedQueuedTaskCount() != null && snapshot.getAgedQueuedTaskCount() > 0) {
            syncGovernanceAlertService.openOrRefreshAlert(
                    null,
                    snapshot.getOldestQueuedTaskId(),
                    SyncAlertType.QUEUE_AGING.name(),
                    SyncAlertSeverity.WARNING.name(),
                    "QUEUE_AGING:GLOBAL",
                    "同步队列中存在老化任务",
                    "当前已识别老化排队任务数=" + snapshot.getAgedQueuedTaskCount()
                            + "，最老任务等待秒数=" + snapshot.getOldestQueuedDurationSeconds()
                            + "，建议=" + snapshot.getRecommendation(),
                    SyncPermissionResource.SYNC_QUEUE.name(),
                    SyncAuditAction.INSPECT_QUEUE_HEALTH.name(),
                    actorId,
                    actorRole
            );
        }
    }

    /**
     * 针对单个老化任务生成细粒度告警。
     *
     * <p>这种任务级告警和全局快照告警同时存在时，前者更适合落到具体工单，
     * 后者更适合做运营态势看板。两者并不冲突，而是服务不同运营视角。
     */
    public SyncGovernanceAlert openQueueAgingAlert(SyncTask task,
                                                  LocalDateTime now,
                                                  int thresholdSeconds,
                                                  SyncActionRequest request) {
        Long queuedDurationSeconds = syncQueuePolicySupport.computeQueuedDurationSeconds(task, now);
        SyncAlertSeverity severity = queuedDurationSeconds != null && queuedDurationSeconds >= thresholdSeconds * 4L
                ? SyncAlertSeverity.CRITICAL
                : SyncAlertSeverity.WARNING;
        return syncGovernanceAlertService.openOrRefreshAlert(
                task.getTenantId(),
                task.getId(),
                SyncAlertType.QUEUE_AGING.name(),
                severity.name(),
                "QUEUE_AGING:TASK:" + task.getId(),
                "同步任务排队时间超过治理阈值",
                "taskId=" + task.getId()
                        + "，tenantId=" + task.getTenantId()
                        + "，queuedDurationSeconds=" + queuedDurationSeconds
                        + "，agingThresholdSeconds=" + thresholdSeconds
                        + "，note=" + request.getNote(),
                SyncPermissionResource.SYNC_QUEUE.name(),
                SyncAuditAction.SCAN_QUEUE_AGING.name(),
                request.getActorId(),
                request.getActorRole()
        );
    }
}
