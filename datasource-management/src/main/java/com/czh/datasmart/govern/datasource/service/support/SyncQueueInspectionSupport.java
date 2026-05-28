/**
 * @Author : Cui
 * @Date: 2026/05/05 23:22
 * @Description DataSmart Govern Backend - SyncQueueInspectionSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czh.datasmart.govern.datasource.config.SyncExecutorProperties;
import com.czh.datasmart.govern.datasource.controller.dto.SyncActionRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncQueueAgingScanResult;
import com.czh.datasmart.govern.datasource.controller.dto.SyncQueueHealthSnapshot;
import com.czh.datasmart.govern.datasource.entity.SyncTask;
import com.czh.datasmart.govern.datasource.mapper.SyncTaskMapper;
import com.czh.datasmart.govern.datasource.support.SyncAuditAction;
import com.czh.datasmart.govern.datasource.support.SyncTaskState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 同步队列健康巡检支撑组件。
 *
 * <p>该组件负责把“队列是否积压、是否达到容量上限、是否需要告警、哪些任务已经老化”从主服务中拆出。
 * 对真实商业化同步平台来说，队列不是一个简单的状态字段，而是运营可靠性的核心观察面：
 * 1. 全局积压表示平台总体吞吐可能不足；
 * 2. 租户积压表示某个客户可能正在占用过多调度资源；
 * 3. 老化任务表示任务长时间没有被执行器领取，可能是容量不足、执行器失联或优先级策略不合理；
 * 4. 巡检动作本身也需要审计，便于后续复盘“谁在什么时候发现了什么队列压力”。
 *
 * <p>后续如果要接入 Micrometer、Prometheus、Grafana、告警中心、租户配额面板或自动扩容策略，
 * 可以优先扩展该组件，而不是把观测逻辑散落在任务状态机里。
 */
@Component
@RequiredArgsConstructor
public class SyncQueueInspectionSupport {

    /**
     * 同步任务 Mapper，用于读取 QUEUED 任务、标记老化任务和选择审计锚点。
     */
    private final SyncTaskMapper syncTaskMapper;

    /**
     * 执行器/队列配置。
     * 这里读取全局队列容量、租户队列容量等运行保护参数。
     */
    private final SyncExecutorProperties syncExecutorProperties;

    /**
     * 队列策略组件。
     * 负责解析阈值、计算排队时长、生成建议和老化说明。
     */
    private final SyncQueuePolicySupport syncQueuePolicySupport;

    /**
     * 队列告警组件。
     * 当前先落本地审计/告警记录，后续可接入统一告警中心。
     */
    private final SyncQueueAlertSupport syncQueueAlertSupport;

    /**
     * 队列容量组件。
     * 这里复用它的租户计数能力，避免每个巡检方法都自己写一套聚合逻辑。
     */
    private final SyncQueueCapacitySupport syncQueueCapacitySupport;

    /**
     * 权限组件。
     * 队列健康和老化扫描属于运营能力，不应该开放给普通业务用户。
     */
    private final SyncTaskPermissionSupport syncTaskPermissionSupport;

    /**
     * 审计组件。
     * 巡检动作虽然不一定修改任务，但会产生运营判断，仍应形成审计轨迹。
     */
    private final SyncAuditSupport syncAuditSupport;

    public SyncQueueHealthSnapshot inspectQueueHealth(SyncActionRequest request) {
        syncTaskPermissionSupport.assertQueueHealthPermission(request.getActorRole(), request.getActorTenantId());
        LocalDateTime now = LocalDateTime.now();
        int agingThresholdSeconds = syncQueuePolicySupport.resolveQueuedTaskAgingThresholdSeconds();
        int queueAlertThresholdGlobal = syncQueuePolicySupport.resolveQueueAlertThresholdGlobal();
        int queueAlertThresholdPerTenant = syncQueuePolicySupport.resolveQueueAlertThresholdPerTenant();

        List<SyncTask> queuedTasks = syncTaskMapper.selectList(new LambdaQueryWrapper<SyncTask>()
                .eq(SyncTask::getCurrentState, SyncTaskState.QUEUED.name())
                .orderByAsc(SyncTask::getQueuedAt)
                .orderByAsc(SyncTask::getCreateTime));
        long globalQueuedCount = queuedTasks.size();
        Map<Long, Long> tenantQueuedCountMap = syncQueueCapacitySupport.summarizeTenantTaskCount(queuedTasks);

        Long highestBacklogTenantId = null;
        long highestTenantQueuedCount = 0L;
        for (Map.Entry<Long, Long> entry : tenantQueuedCountMap.entrySet()) {
            Long tenantBucket = entry.getKey();
            if (tenantBucket == null || tenantBucket < 0) {
                continue;
            }
            if (entry.getValue() > highestTenantQueuedCount) {
                highestBacklogTenantId = tenantBucket;
                highestTenantQueuedCount = entry.getValue();
            }
        }

        SyncTask oldestQueuedTask = queuedTasks.isEmpty() ? null : queuedTasks.get(0);
        long agedQueuedTaskCount = queuedTasks.stream()
                .filter(task -> syncQueuePolicySupport.isQueuedTaskAged(task, now, agingThresholdSeconds))
                .count();

        int maxQueuedTasksGlobal = syncExecutorProperties.getMaxQueuedTasksGlobal() == null
                ? 0
                : syncExecutorProperties.getMaxQueuedTasksGlobal();
        int maxQueuedTasksPerTenant = syncExecutorProperties.getMaxQueuedTasksPerTenant() == null
                ? 0
                : syncExecutorProperties.getMaxQueuedTasksPerTenant();
        boolean globalAlertTriggered = queueAlertThresholdGlobal > 0 && globalQueuedCount >= queueAlertThresholdGlobal;
        boolean tenantAlertTriggered = queueAlertThresholdPerTenant > 0
                && highestTenantQueuedCount >= queueAlertThresholdPerTenant;
        boolean globalSaturated = maxQueuedTasksGlobal > 0 && globalQueuedCount >= maxQueuedTasksGlobal;
        boolean tenantSaturated = maxQueuedTasksPerTenant > 0 && highestTenantQueuedCount >= maxQueuedTasksPerTenant;

        SyncQueueHealthSnapshot snapshot = buildQueueHealthSnapshot(
                globalQueuedCount, maxQueuedTasksGlobal, queueAlertThresholdGlobal,
                highestBacklogTenantId, highestTenantQueuedCount, maxQueuedTasksPerTenant,
                queueAlertThresholdPerTenant, agedQueuedTaskCount, oldestQueuedTask, now,
                globalAlertTriggered, tenantAlertTriggered, globalSaturated, tenantSaturated);
        syncQueueAlertSupport.openQueueHealthAlerts(snapshot, request.getActorId(), request.getActorRole(), highestBacklogTenantId);
        recordQueueHealthAudit(request, oldestQueuedTask, highestBacklogTenantId, highestTenantQueuedCount, agedQueuedTaskCount,
                globalQueuedCount, snapshot);
        return snapshot;
    }

    public SyncQueueAgingScanResult scanQueuedTaskAging(SyncActionRequest request) {
        syncTaskPermissionSupport.assertQueueAgingPermission(request.getActorRole(), request.getActorTenantId());
        LocalDateTime now = LocalDateTime.now();
        int thresholdSeconds = syncQueuePolicySupport.resolveQueuedTaskAgingThresholdSeconds();
        int scanLimit = syncQueuePolicySupport.resolveQueuedTaskAgingScanLimit();
        LocalDateTime agingDeadline = now.minusSeconds(thresholdSeconds);

        long queuedTaskCount = syncTaskMapper.selectCount(new LambdaQueryWrapper<SyncTask>()
                .eq(SyncTask::getCurrentState, SyncTaskState.QUEUED.name()));
        List<SyncTask> agedTasks = syncTaskMapper.selectList(new LambdaQueryWrapper<SyncTask>()
                .eq(SyncTask::getCurrentState, SyncTaskState.QUEUED.name())
                .isNotNull(SyncTask::getQueuedAt)
                .lt(SyncTask::getQueuedAt, agingDeadline)
                .orderByAsc(SyncTask::getQueuedAt)
                .orderByAsc(SyncTask::getCreateTime)
                .last("LIMIT " + scanLimit));

        int markedAttentionTaskCount = 0;
        List<Long> taskIds = new ArrayList<>();
        for (SyncTask task : agedTasks) {
            boolean newlyMarked = !Boolean.TRUE.equals(task.getOperatorAttentionRequired());
            task.setOperatorAttentionRequired(true);
            task.setIncidentNote(syncQueuePolicySupport.buildQueueAgingIncidentNote(task, now, thresholdSeconds, request.getNote()));
            task.setUpdatedBy(request.getActorId());
            syncTaskMapper.updateById(task);
            if (newlyMarked) {
                markedAttentionTaskCount++;
            }
            taskIds.add(task.getId());
            syncAuditSupport.recordAudit(task, task.getLastExecutionId(), SyncAuditAction.SCAN_QUEUE_AGING,
                    request.getActorId(), request.getActorRole(),
                    syncAuditSupport.buildPayload(
                            "queuedAt", task.getQueuedAt(),
                            "queuedDurationSeconds", syncQueuePolicySupport.computeQueuedDurationSeconds(task, now),
                            "thresholdSeconds", thresholdSeconds,
                            "newlyMarked", newlyMarked,
                            "note", request.getNote()
                    ));
        }

        SyncQueueAgingScanResult result = new SyncQueueAgingScanResult();
        result.setQueuedTaskCount(queuedTaskCount);
        result.setScanLimit(scanLimit);
        result.setAgedQueuedTaskCount(agedTasks.size());
        result.setMarkedAttentionTaskCount(markedAttentionTaskCount);
        result.setThresholdSeconds(thresholdSeconds);
        result.setOldestAgedQueuedAt(agedTasks.isEmpty() ? null : agedTasks.get(0).getQueuedAt());
        result.setTaskIds(taskIds);
        result.setAlertSuggested(agedTasks.size() >= Math.max(3, scanLimit / 5));
        for (SyncTask task : agedTasks) {
            syncQueueAlertSupport.openQueueAgingAlert(task, now, thresholdSeconds, request);
        }
        return result;
    }

    private SyncQueueHealthSnapshot buildQueueHealthSnapshot(long globalQueuedCount,
                                                             int maxQueuedTasksGlobal,
                                                             int queueAlertThresholdGlobal,
                                                             Long highestBacklogTenantId,
                                                             long highestTenantQueuedCount,
                                                             int maxQueuedTasksPerTenant,
                                                             int queueAlertThresholdPerTenant,
                                                             long agedQueuedTaskCount,
                                                             SyncTask oldestQueuedTask,
                                                             LocalDateTime now,
                                                             boolean globalAlertTriggered,
                                                             boolean tenantAlertTriggered,
                                                             boolean globalSaturated,
                                                             boolean tenantSaturated) {
        SyncQueueHealthSnapshot snapshot = new SyncQueueHealthSnapshot();
        snapshot.setGlobalQueuedCount(globalQueuedCount);
        snapshot.setMaxQueuedTasksGlobal(maxQueuedTasksGlobal);
        snapshot.setQueueAlertThresholdGlobal(queueAlertThresholdGlobal);
        snapshot.setHighestBacklogTenantId(highestBacklogTenantId);
        snapshot.setHighestBacklogTenantQueuedCount(highestTenantQueuedCount);
        snapshot.setMaxQueuedTasksPerTenant(maxQueuedTasksPerTenant);
        snapshot.setQueueAlertThresholdPerTenant(queueAlertThresholdPerTenant);
        snapshot.setAgedQueuedTaskCount(agedQueuedTaskCount);
        snapshot.setOldestQueuedTaskId(oldestQueuedTask == null ? null : oldestQueuedTask.getId());
        snapshot.setOldestQueuedAt(oldestQueuedTask == null ? null : oldestQueuedTask.getQueuedAt());
        snapshot.setOldestQueuedDurationSeconds(syncQueuePolicySupport.computeQueuedDurationSeconds(oldestQueuedTask, now));
        snapshot.setPressureLevel(syncQueuePolicySupport.resolveQueuePressureLevel(globalAlertTriggered, tenantAlertTriggered,
                globalSaturated, tenantSaturated, agedQueuedTaskCount));
        snapshot.setAttentionRequired(globalAlertTriggered || tenantAlertTriggered
                || globalSaturated || tenantSaturated || agedQueuedTaskCount > 0);
        snapshot.setRecommendation(syncQueuePolicySupport.buildQueueHealthRecommendation(globalAlertTriggered, tenantAlertTriggered,
                globalSaturated, tenantSaturated, agedQueuedTaskCount,
                snapshot.getOldestQueuedDurationSeconds(), highestBacklogTenantId));
        return snapshot;
    }

    private void recordQueueHealthAudit(SyncActionRequest request,
                                        SyncTask oldestQueuedTask,
                                        Long highestBacklogTenantId,
                                        long highestTenantQueuedCount,
                                        long agedQueuedTaskCount,
                                        long globalQueuedCount,
                                        SyncQueueHealthSnapshot snapshot) {
        SyncTask auditAnchor = oldestQueuedTask == null ? findMostRecentlyCreatedTask() : oldestQueuedTask;
        if (auditAnchor == null) {
            return;
        }
        syncAuditSupport.recordAudit(auditAnchor, auditAnchor.getLastExecutionId(), SyncAuditAction.INSPECT_QUEUE_HEALTH,
                request.getActorId(), request.getActorRole(),
                syncAuditSupport.buildPayload(
                        "globalQueuedCount", globalQueuedCount,
                        "highestBacklogTenantId", highestBacklogTenantId,
                        "highestTenantQueuedCount", highestTenantQueuedCount,
                        "agedQueuedTaskCount", agedQueuedTaskCount,
                        "pressureLevel", snapshot.getPressureLevel()
                ));
    }

    private SyncTask findMostRecentlyCreatedTask() {
        List<SyncTask> tasks = syncTaskMapper.selectList(new LambdaQueryWrapper<SyncTask>()
                .orderByDesc(SyncTask::getCreateTime)
                .last("LIMIT 1"));
        return tasks.isEmpty() ? null : tasks.get(0);
    }
}
