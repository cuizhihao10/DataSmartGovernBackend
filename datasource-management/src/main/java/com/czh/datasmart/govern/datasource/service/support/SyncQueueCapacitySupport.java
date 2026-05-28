/**
 * @Author : Cui
 * @Date: 2026/05/05 18:46
 * @Description DataSmart Govern Backend - SyncQueueCapacitySupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czh.datasmart.govern.datasource.config.SyncExecutorProperties;
import com.czh.datasmart.govern.datasource.controller.dto.SyncExecutorClaimRequest;
import com.czh.datasmart.govern.datasource.entity.SyncTask;
import com.czh.datasmart.govern.datasource.entity.SyncTemplate;
import com.czh.datasmart.govern.datasource.mapper.SyncTaskMapper;
import com.czh.datasmart.govern.datasource.mapper.SyncTemplateMapper;
import com.czh.datasmart.govern.datasource.support.PriorityLevel;
import com.czh.datasmart.govern.datasource.support.SyncTaskState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * 同步队列容量与候选认领支持组件。
 *
 * <p>该组件专门回答三个问题：
 * 1. 当前任务是否还能进入待执行队列；
 * 2. 当前任务是否还能开始占用执行资源；
 * 3. 执行器下一次应该认领哪一个候选任务。
 *
 * <p>这些逻辑被拆出 `SyncTaskServiceImpl` 的原因，是它们本质上属于“调度策略”和“容量治理”，
 * 而不是任务状态机本身。真实商业化数据同步平台后续通常会继续扩展：
 * 1. 多执行器池隔离，例如离线批处理池、CDC 实时池、轻量 API 同步池；
 * 2. 租户套餐配额，例如基础版、专业版、专属资源版；
 * 3. 数据源保护，例如同一个 MySQL/PostgreSQL/Kafka 集群不能被过多同步任务同时打满；
 * 4. 公平调度，例如避免大租户任务洪峰把小租户任务完全淹没。
 *
 * <p>提前把它独立成组件，可以让后续调度器升级时只替换策略类，而不反复改动主服务事务流程。
 */
@Component
@RequiredArgsConstructor
public class SyncQueueCapacitySupport {

    /**
     * 同步执行器配置。
     *
     * <p>当前配置来自 Spring Boot application.yml。
     * 后续商业化版本可迁移到租户策略中心、执行器池配置中心或动态限流服务。
     */
    private final SyncExecutorProperties syncExecutorProperties;

    /**
     * 同步任务 Mapper。
     *
     * <p>这里直接使用 Mapper，而不是依赖 `ServiceImpl.list/count`，是为了让容量组件脱离主服务继承体系，
     * 后续可以单独测试或被真正的调度器复用。
     */
    private final SyncTaskMapper syncTaskMapper;

    /**
     * 同步模板 Mapper。
     *
     * <p>认领候选过滤需要读取模板的同步模式、源数据源和目标数据源，
     * 以判断执行器是否支持、数据源并发是否已经饱和。
     */
    private final SyncTemplateMapper syncTemplateMapper;

    /**
     * 选择下一条可被执行器认领的任务。
     *
     * <p>当前先采用数据库粗过滤 + Java 侧优先级排序的基线实现：
     * 1. 数据库负责按状态、租户和启用状态缩小候选范围；
     * 2. Java 侧负责校验执行器能力、租户并发、数据源并发和公平排序；
     * 3. 如果开启租户公平性，会先为每个租户选一个代表候选，再在代表候选之间排序。
     *
     * <p>这种实现适合当前阶段快速落产品语义。
     * 如果后续任务量显著增长，就应继续演进成专用队列表、Redis/ZSet 调度队列或独立调度器服务。
     */
    public ClaimCandidate selectNextClaimableTask(SyncExecutorClaimRequest request) {
        int scanLimit = syncExecutorProperties.getClaimScanLimit() == null
                ? 50
                : Math.max(1, syncExecutorProperties.getClaimScanLimit());

        LambdaQueryWrapper<SyncTask> wrapper = new LambdaQueryWrapper<SyncTask>()
                .eq(SyncTask::getCurrentState, SyncTaskState.QUEUED.name())
                .eq(SyncTask::getEnabled, true)
                .eq(request.getTenantId() != null, SyncTask::getTenantId, request.getTenantId())
                .orderByAsc(SyncTask::getQueuedAt)
                .orderByAsc(SyncTask::getNextRunAt)
                .orderByAsc(SyncTask::getCreateTime)
                .last("LIMIT " + scanLimit);

        List<SyncTask> candidates = syncTaskMapper.selectList(wrapper);
        List<SyncTask> activeTasks = listActiveCapacityTasks();
        Map<Long, SyncTemplate> activeTemplateMap = loadTemplateMap(activeTasks);
        List<ClaimCandidate> claimableCandidates = candidates.stream()
                .map(task -> new ClaimCandidate(task, getRequiredEnabledTemplate(task.getTemplateId())))
                .filter(candidate -> matchesClaimRequest(candidate, request))
                .filter(candidate -> !reachesTenantConcurrencyLimit(candidate.task(), activeTasks))
                .filter(candidate -> !reachesDatasourceConcurrencyLimit(candidate.task(), candidate.template(), activeTasks, activeTemplateMap))
                .toList();

        if (claimableCandidates.isEmpty()) {
            return null;
        }

        Map<Long, Long> tenantActiveCountMap = summarizeTenantTaskCount(activeTasks);
        Comparator<ClaimCandidate> claimComparator = buildClaimCandidateComparator(tenantActiveCountMap);
        if (Boolean.TRUE.equals(syncExecutorProperties.getEnableTenantQueueFairness())) {
            return pickTenantFairCandidate(claimableCandidates, claimComparator);
        }

        return claimableCandidates.stream()
                .sorted(claimComparator)
                .findFirst()
                .orElse(null);
    }

    /**
     * 校验任务入队容量。
     *
     * <p>入队保护关注的是“待执行队列不能无限膨胀”。
     * 即使执行器短暂不可用，上游也不能无止境提交任务，否则恢复后会形成雪崩式积压。
     */
    public void ensureQueueCapacity(SyncTask task) {
        QueuePressureSnapshot snapshot = inspectQueuePressure(task.getTenantId(), task.getId());
        if (snapshot.reachesGlobalLimit()) {
            throw new IllegalStateException("当前平台待执行队列已达到全局上限，globalQueuedCount="
                    + snapshot.globalQueuedCount() + ", limit=" + snapshot.maxQueuedTasksGlobal());
        }
        if (snapshot.reachesTenantLimit()) {
            throw new IllegalStateException("当前租户待执行队列已达到上限，tenantId=" + task.getTenantId()
                    + ", tenantQueuedCount=" + snapshot.tenantQueuedCount()
                    + ", limit=" + snapshot.maxQueuedTasksPerTenant());
        }
    }

    /**
     * 校验任务运行并发容量。
     *
     * <p>运行中容量保护关注的是“正在占用资源的任务不能太多”。
     * 这里同时检查租户维度和数据源维度，避免单租户或单数据源被同步任务瞬间打满。
     */
    public void ensureConcurrencyCapacity(SyncTask task, SyncTemplate template) {
        List<SyncTask> activeTasks = listActiveCapacityTasks();
        if (reachesTenantConcurrencyLimit(task, activeTasks)) {
            int limit = syncExecutorProperties.getMaxRunningTasksPerTenant() == null
                    ? 0
                    : syncExecutorProperties.getMaxRunningTasksPerTenant();
            throw new IllegalStateException("当前租户活跃同步任务已达到并发上限，tenantId=" + task.getTenantId() + ", limit=" + limit);
        }

        Map<Long, SyncTemplate> activeTemplateMap = loadTemplateMap(activeTasks);
        Long saturatedDatasourceId = findSaturatedDatasourceId(task, template, activeTasks, activeTemplateMap);
        if (saturatedDatasourceId != null) {
            int limit = syncExecutorProperties.getMaxRunningTasksPerDatasource() == null
                    ? 0
                    : syncExecutorProperties.getMaxRunningTasksPerDatasource();
            throw new IllegalStateException("数据源活跃同步任务已达到并发上限，datasourceId="
                    + saturatedDatasourceId + ", limit=" + limit);
        }
    }

    /**
     * 查看队列压力。
     *
     * <p>该方法既被入队保护使用，也被租约恢复使用。
     * `excludedTaskId` 用于排除当前正在被检查的任务，避免“自己已经在队列里”导致误判队列已满。
     */
    public QueuePressureSnapshot inspectQueuePressure(Long tenantId, Long excludedTaskId) {
        Integer maxQueuedTasksGlobal = syncExecutorProperties.getMaxQueuedTasksGlobal();
        Integer maxQueuedTasksPerTenant = syncExecutorProperties.getMaxQueuedTasksPerTenant();

        long globalQueuedCount = syncTaskMapper.selectCount(new LambdaQueryWrapper<SyncTask>()
                .eq(SyncTask::getCurrentState, SyncTaskState.QUEUED.name())
                .ne(excludedTaskId != null, SyncTask::getId, excludedTaskId));

        long tenantQueuedCount = tenantId == null ? 0L : syncTaskMapper.selectCount(new LambdaQueryWrapper<SyncTask>()
                .eq(SyncTask::getCurrentState, SyncTaskState.QUEUED.name())
                .eq(SyncTask::getTenantId, tenantId)
                .ne(excludedTaskId != null, SyncTask::getId, excludedTaskId));

        boolean reachesGlobalLimit = maxQueuedTasksGlobal != null
                && maxQueuedTasksGlobal > 0
                && globalQueuedCount >= maxQueuedTasksGlobal;
        boolean reachesTenantLimit = tenantId != null
                && maxQueuedTasksPerTenant != null
                && maxQueuedTasksPerTenant > 0
                && tenantQueuedCount >= maxQueuedTasksPerTenant;

        return new QueuePressureSnapshot(
                globalQueuedCount,
                tenantQueuedCount,
                maxQueuedTasksGlobal == null ? 0 : maxQueuedTasksGlobal,
                maxQueuedTasksPerTenant == null ? 0 : maxQueuedTasksPerTenant,
                reachesGlobalLimit,
                reachesTenantLimit
        );
    }

    /**
     * 按租户汇总任务数量。
     *
     * <p>该方法被队列健康快照和公平调度共同使用。
     * 对于没有 tenantId 的任务，会落到一个稳定的内部桶，避免空值导致统计失败。
     */
    public Map<Long, Long> summarizeTenantTaskCount(List<SyncTask> tasks) {
        Map<Long, Long> tenantCountMap = new HashMap<>();
        for (SyncTask task : tasks) {
            Long tenantBucket = resolveTenantBucket(task);
            tenantCountMap.put(tenantBucket, tenantCountMap.getOrDefault(tenantBucket, 0L) + 1L);
        }
        return tenantCountMap;
    }

    /**
     * 当前版本先用“主状态 + 租约是否仍有效”近似表达活跃资源占用。
     *
     * <p>这样可以把已经过期但尚未清理的僵尸任务排除出去，避免它们长期卡死新的调度机会。
     */
    private List<SyncTask> listActiveCapacityTasks() {
        return syncTaskMapper.selectList(new LambdaQueryWrapper<SyncTask>()
                .in(SyncTask::getCurrentState, List.of(SyncTaskState.RUNNING.name(), SyncTaskState.RETRYING.name()))
                .orderByAsc(SyncTask::getTenantId)
                .orderByAsc(SyncTask::getId))
                .stream()
                .filter(this::occupiesConcurrencySlot)
                .toList();
    }

    /**
     * 第一版公平调度不做复杂抢占，而是先给每个租户一个“代表候选”。
     */
    private ClaimCandidate pickTenantFairCandidate(List<ClaimCandidate> claimableCandidates,
                                                   Comparator<ClaimCandidate> claimComparator) {
        Map<Long, ClaimCandidate> representativeMap = new HashMap<>();
        for (ClaimCandidate candidate : claimableCandidates) {
            Long tenantBucket = resolveTenantBucket(candidate.task());
            ClaimCandidate current = representativeMap.get(tenantBucket);
            if (current == null || claimComparator.compare(candidate, current) < 0) {
                representativeMap.put(tenantBucket, candidate);
            }
        }

        return representativeMap.values().stream()
                .sorted(claimComparator)
                .findFirst()
                .orElse(null);
    }

    private Comparator<ClaimCandidate> buildClaimCandidateComparator(Map<Long, Long> tenantActiveCountMap) {
        return Comparator
                .comparingInt((ClaimCandidate candidate) -> priorityWeight(candidate.task().getPriority())).reversed()
                .thenComparingLong(candidate -> tenantActiveCountMap.getOrDefault(resolveTenantBucket(candidate.task()), 0L))
                .thenComparing(candidate -> candidate.task().getQueuedAt(),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(candidate -> candidate.task().getNextRunAt(),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(candidate -> candidate.task().getCreateTime(),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(candidate -> candidate.task().getId(),
                        Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private boolean matchesClaimRequest(ClaimCandidate candidate, SyncExecutorClaimRequest request) {
        if (request.getSupportedRunModes() != null && !request.getSupportedRunModes().isEmpty()) {
            boolean matchedRunMode = request.getSupportedRunModes().stream()
                    .filter(item -> item != null && !item.isBlank())
                    .anyMatch(item -> item.equalsIgnoreCase(candidate.task().getRunMode()));
            if (!matchedRunMode) {
                return false;
            }
        }

        if (request.getSupportedSyncModes() != null && !request.getSupportedSyncModes().isEmpty()) {
            boolean matchedSyncMode = request.getSupportedSyncModes().stream()
                    .filter(item -> item != null && !item.isBlank())
                    .anyMatch(item -> item.equalsIgnoreCase(candidate.template().getSyncMode()));
            if (!matchedSyncMode) {
                return false;
            }
        }
        return true;
    }

    private boolean reachesTenantConcurrencyLimit(SyncTask candidate, List<SyncTask> activeTasks) {
        Integer limit = syncExecutorProperties.getMaxRunningTasksPerTenant();
        if (limit == null || limit <= 0 || candidate.getTenantId() == null) {
            return false;
        }

        long activeCount = activeTasks.stream()
                .filter(task -> !task.getId().equals(candidate.getId()))
                .filter(task -> candidate.getTenantId().equals(task.getTenantId()))
                .count();
        return activeCount >= limit;
    }

    private boolean reachesDatasourceConcurrencyLimit(SyncTask candidate, SyncTemplate candidateTemplate,
                                                      List<SyncTask> activeTasks, Map<Long, SyncTemplate> activeTemplateMap) {
        return findSaturatedDatasourceId(candidate, candidateTemplate, activeTasks, activeTemplateMap) != null;
    }

    private Long findSaturatedDatasourceId(SyncTask candidate, SyncTemplate candidateTemplate,
                                           List<SyncTask> activeTasks, Map<Long, SyncTemplate> activeTemplateMap) {
        Integer limit = syncExecutorProperties.getMaxRunningTasksPerDatasource();
        if (limit == null || limit <= 0 || candidateTemplate == null) {
            return null;
        }

        Set<Long> candidateDatasourceIds = new LinkedHashSet<>();
        if (candidateTemplate.getSourceDatasourceId() != null) {
            candidateDatasourceIds.add(candidateTemplate.getSourceDatasourceId());
        }
        if (candidateTemplate.getTargetDatasourceId() != null) {
            candidateDatasourceIds.add(candidateTemplate.getTargetDatasourceId());
        }
        if (candidateDatasourceIds.isEmpty()) {
            return null;
        }

        for (Long datasourceId : candidateDatasourceIds) {
            long activeCount = activeTasks.stream()
                    .filter(task -> !task.getId().equals(candidate.getId()))
                    .filter(task -> taskTouchesDatasource(activeTemplateMap.get(task.getTemplateId()), datasourceId))
                    .count();
            if (activeCount >= limit) {
                return datasourceId;
            }
        }
        return null;
    }

    private Map<Long, SyncTemplate> loadTemplateMap(List<SyncTask> tasks) {
        Set<Long> templateIds = new LinkedHashSet<>();
        for (SyncTask task : tasks) {
            if (task.getTemplateId() != null) {
                templateIds.add(task.getTemplateId());
            }
        }

        Map<Long, SyncTemplate> templateMap = new HashMap<>();
        if (templateIds.isEmpty()) {
            return templateMap;
        }

        List<SyncTemplate> templates = syncTemplateMapper.selectBatchIds(templateIds);
        for (SyncTemplate template : templates) {
            templateMap.put(template.getId(), template);
        }
        return templateMap;
    }

    private SyncTemplate getRequiredEnabledTemplate(Long templateId) {
        SyncTemplate template = syncTemplateMapper.selectById(templateId);
        if (template == null || !Boolean.TRUE.equals(template.getEnabled())) {
            throw new NoSuchElementException("同步模板不存在或未启用: " + templateId);
        }
        return template;
    }

    private Long resolveTenantBucket(SyncTask task) {
        if (task == null) {
            return Long.MIN_VALUE;
        }
        if (task.getTenantId() != null) {
            return task.getTenantId();
        }
        return task.getId() == null ? Long.MIN_VALUE : -task.getId();
    }

    private boolean taskTouchesDatasource(SyncTemplate template, Long datasourceId) {
        if (template == null || datasourceId == null) {
            return false;
        }
        return datasourceId.equals(template.getSourceDatasourceId())
                || datasourceId.equals(template.getTargetDatasourceId());
    }

    private boolean occupiesConcurrencySlot(SyncTask task) {
        if (task == null) {
            return false;
        }
        SyncTaskState state = SyncTaskState.fromValue(task.getCurrentState());
        if (state != SyncTaskState.RUNNING && state != SyncTaskState.RETRYING) {
            return false;
        }
        LocalDateTime leaseExpireAt = task.getDispatchLeaseExpireAt();
        return leaseExpireAt == null || leaseExpireAt.isAfter(LocalDateTime.now());
    }

    private int priorityWeight(String priority) {
        if (priority == null || priority.isBlank()) {
            return 0;
        }
        return switch (PriorityLevel.fromValue(priority)) {
            case URGENT -> 4;
            case HIGH -> 3;
            case MEDIUM -> 2;
            case LOW -> 1;
        };
    }

    /**
     * 执行器可认领候选。
     *
     * <p>用 record 保持不可变语义，避免候选在排序或过滤过程中被意外改写。
     */
    public record ClaimCandidate(SyncTask task, SyncTemplate template) {
    }

    /**
     * 队列压力快照。
     *
     * <p>该快照用于表达“当前队列距离保护阈值还有多远”，既能给入队保护用，
     * 也能给租约恢复判断是否允许自动重新入队。
     */
    public record QueuePressureSnapshot(long globalQueuedCount,
                                        long tenantQueuedCount,
                                        int maxQueuedTasksGlobal,
                                        int maxQueuedTasksPerTenant,
                                        boolean reachesGlobalLimit,
                                        boolean reachesTenantLimit) {
    }
}
