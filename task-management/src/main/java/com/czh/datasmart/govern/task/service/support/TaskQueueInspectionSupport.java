package com.czh.datasmart.govern.task.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.czh.datasmart.govern.task.controller.dto.TaskActorContext;
import com.czh.datasmart.govern.task.controller.dto.TaskQueueInspectionRequest;
import com.czh.datasmart.govern.task.controller.dto.TaskQueueItemView;
import com.czh.datasmart.govern.task.controller.dto.TaskQueueSummaryResponse;
import com.czh.datasmart.govern.task.entity.Task;
import com.czh.datasmart.govern.task.mapper.TaskMapper;
import com.czh.datasmart.govern.task.service.assembler.TaskQueueItemViewAssembler;
import com.czh.datasmart.govern.task.support.TaskPriority;
import com.czh.datasmart.govern.task.support.TaskStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * @Author : Cui
 * @Date: 2026/05/05 23:50
 * @Description DataSmart Govern Backend - TaskQueueInspectionSupport.java
 * @Version:1.0.0
 *
 * 任务队列运营视图支持组件。
 *
 * <p>普通任务列表回答“系统里有哪些任务”，队列运营视图回答“哪些任务正在影响调度健康”。
 * 这类查询会服务于运维工作台、SLA 大盘、告警规则、容量治理和事故复盘，因此不应该散落在主服务里。
 *
 * <p>当前实现仍然使用 MyBatis-Plus Wrapper 构造查询，便于学习和维护。
 * 当任务量进入百万级或需要复杂聚合时，可以把状态分布、TopN、老化任务扫描下沉到 Mapper SQL、
 * 物化统计表、Redis 指标快照或 observability 模块。
 */
@Component
@RequiredArgsConstructor
public class TaskQueueInspectionSupport {

    /**
     * 队列视图默认关注仍然可能影响调度健康的状态。
     */
    private static final Set<String> DEFAULT_OPERATIONAL_QUEUE_STATUSES = Set.of(
            TaskStatus.PENDING,
            TaskStatus.RUNNING,
            TaskStatus.DEFERRED,
            TaskStatus.DEAD_LETTER,
            TaskStatus.FAILED,
            TaskStatus.PAUSED
    );

    /**
     * 状态汇总的稳定输出顺序。
     *
     * <p>使用 List 而不是 Set，是为了让 API 响应对前端图表、测试断言和人工阅读都更稳定。
     */
    private static final List<String> QUEUE_SUMMARY_STATUS_ORDER = List.of(
            TaskStatus.PENDING,
            TaskStatus.RUNNING,
            TaskStatus.DEFERRED,
            TaskStatus.DEAD_LETTER,
            TaskStatus.FAILED,
            TaskStatus.PAUSED,
            TaskStatus.RETRYING,
            TaskStatus.SUCCESS,
            TaskStatus.CANCELLED
    );

    private final TaskMapper taskMapper;
    private final TaskQueueItemViewAssembler taskQueueItemViewAssembler;
    private final TaskDataScopeSupport dataScopeSupport;

    /**
     * 查询原始任务队列分页。
     *
     * <p>默认限制最大 page size 为 200，是为了避免运营排障接口本身成为数据库压力来源。
     */
    public IPage<Task> inspectQueue(TaskQueueInspectionRequest request, TaskActorContext actorContext) {
        TaskQueueInspectionRequest safeRequest = request == null ? new TaskQueueInspectionRequest() : request;
        int current = Math.max(1, safeRequest.getCurrent() == null ? 1 : safeRequest.getCurrent());
        int size = Math.max(1, Math.min(safeRequest.getSize() == null ? 20 : safeRequest.getSize(), 200));
        return taskMapper.selectPage(new Page<>(current, size), buildQueueInspectionWrapper(safeRequest, true, actorContext));
    }

    /**
     * 查询适合运营工作台展示的队列项视图。
     *
     * <p>该方法复用原始队列分页结果，并在 DTO 组装阶段补充排队时长、租约剩余时间、
     * 风险原因和推荐动作，避免 Controller 理解任务调度细节。
     */
    public IPage<TaskQueueItemView> inspectQueueItems(TaskQueueInspectionRequest request, TaskActorContext actorContext) {
        LocalDateTime now = LocalDateTime.now();
        return inspectQueue(request, actorContext).convert(task -> taskQueueItemViewAssembler.toView(task, now));
    }

    /**
     * 查询队列健康汇总。
     *
     * <p>这里不拉取全量任务再在内存中统计，而是使用多次轻量 count/top1 查询。
     * 对生产系统来说，排障接口必须比普通业务接口更克制，否则在事故期间会继续放大数据库压力。
     */
    public TaskQueueSummaryResponse summarizeQueue(TaskQueueInspectionRequest request, TaskActorContext actorContext) {
        TaskQueueInspectionRequest safeRequest = request == null ? new TaskQueueInspectionRequest() : request;
        LocalDateTime now = LocalDateTime.now();

        TaskQueueSummaryResponse summary = new TaskQueueSummaryResponse();
        summary.setGeneratedAt(now);
        summary.setTotalCount(taskMapper.selectCount(buildQueueInspectionWrapper(safeRequest, false, actorContext)));

        Map<String, Long> statusCounts = calculateStatusCounts(safeRequest, actorContext);
        summary.setStatusCounts(statusCounts);
        summary.setPendingCount(statusCounts.getOrDefault(TaskStatus.PENDING, 0L));
        summary.setRunningCount(statusCounts.getOrDefault(TaskStatus.RUNNING, 0L));
        summary.setDeferredCount(statusCounts.getOrDefault(TaskStatus.DEFERRED, 0L));
        summary.setDeadLetterCount(statusCounts.getOrDefault(TaskStatus.DEAD_LETTER, 0L));
        summary.setFailedCount(statusCounts.getOrDefault(TaskStatus.FAILED, 0L));
        summary.setPausedCount(statusCounts.getOrDefault(TaskStatus.PAUSED, 0L));
        summary.setAttentionRequiredCount(taskMapper.selectCount(buildQueueInspectionWrapper(safeRequest, false, actorContext)
                .eq(Task::getAttentionRequired, true)));

        Task oldestQueuedTask = taskMapper.selectOne(buildQueueInspectionWrapper(safeRequest, false, actorContext)
                .isNotNull(Task::getQueuedTime)
                .orderByAsc(Task::getQueuedTime)
                .last("LIMIT 1"));
        if (oldestQueuedTask != null && oldestQueuedTask.getQueuedTime() != null) {
            summary.setOldestQueuedTime(oldestQueuedTask.getQueuedTime());
            summary.setOldestQueuedAgeSeconds(Duration.between(oldestQueuedTask.getQueuedTime(), now).getSeconds());
        }

        Task maxDeferredTask = taskMapper.selectOne(buildQueueInspectionWrapper(safeRequest, false, actorContext)
                .isNotNull(Task::getDeferCount)
                .orderByDesc(Task::getDeferCount)
                .last("LIMIT 1"));
        summary.setMaxObservedDeferCount(maxDeferredTask == null ? 0 : safeDeferCount(maxDeferredTask));
        return summary;
    }

    private Map<String, Long> calculateStatusCounts(TaskQueueInspectionRequest request, TaskActorContext actorContext) {
        Map<String, Long> statusCounts = new LinkedHashMap<>();
        String requestedStatus = trimToNull(request.getStatus());
        List<String> statuses = requestedStatus == null
                ? QUEUE_SUMMARY_STATUS_ORDER
                : List.of(requestedStatus.toUpperCase(Locale.ROOT));
        for (String status : statuses) {
            Long count = taskMapper.selectCount(buildQueueInspectionWrapper(request, false, actorContext, status));
            if (count > 0 || requestedStatus != null || shouldExposeZeroStatus(request, status)) {
                statusCounts.put(status, count);
            }
        }
        return statusCounts;
    }

    private boolean shouldExposeZeroStatus(TaskQueueInspectionRequest request, String status) {
        return Boolean.TRUE.equals(request.getIncludeTerminal()) || DEFAULT_OPERATIONAL_QUEUE_STATUSES.contains(status);
    }

    private LambdaQueryWrapper<Task> buildQueueInspectionWrapper(TaskQueueInspectionRequest request,
                                                                boolean includeOrdering,
                                                                TaskActorContext actorContext) {
        return buildQueueInspectionWrapper(request, includeOrdering, actorContext, null);
    }

    private LambdaQueryWrapper<Task> buildQueueInspectionWrapper(TaskQueueInspectionRequest request,
                                                                boolean includeOrdering) {
        return buildQueueInspectionWrapper(request, includeOrdering, null, null);
    }

    private LambdaQueryWrapper<Task> buildQueueInspectionWrapper(TaskQueueInspectionRequest request,
                                                                boolean includeOrdering,
                                                                TaskActorContext actorContext,
                                                                String statusOverride) {
        TaskQueueInspectionRequest safeRequest = request == null ? new TaskQueueInspectionRequest() : request;
        LambdaQueryWrapper<Task> wrapper = new LambdaQueryWrapper<>();
        String status = statusOverride == null ? trimToNull(safeRequest.getStatus()) : statusOverride;
        applyQueueStatusFilter(wrapper, status, Boolean.TRUE.equals(safeRequest.getIncludeTerminal()));

        String type = trimToNull(safeRequest.getType());
        if (type != null) {
            wrapper.eq(Task::getType, type);
        }
        String priority = trimToNull(safeRequest.getPriority());
        if (priority != null) {
            wrapper.eq(Task::getPriority, TaskPriority.normalize(priority));
        }
        if (safeRequest.getAttentionRequired() != null) {
            wrapper.eq(Task::getAttentionRequired, safeRequest.getAttentionRequired());
        }
        String currentExecutorId = trimToNull(safeRequest.getCurrentExecutorId());
        if (currentExecutorId != null) {
            wrapper.eq(Task::getCurrentExecutorId, currentExecutorId);
        }
        if (safeRequest.getDeferCountAtLeast() != null) {
            wrapper.ge(Task::getDeferCount, Math.max(0, safeRequest.getDeferCountAtLeast()));
        }
        if (safeRequest.getQueuedAfter() != null) {
            wrapper.ge(Task::getQueuedTime, safeRequest.getQueuedAfter());
        }
        if (safeRequest.getQueuedBefore() != null) {
            wrapper.le(Task::getQueuedTime, safeRequest.getQueuedBefore());
        }
        if (safeRequest.getQueuedOlderThanSeconds() != null) {
            wrapper.le(Task::getQueuedTime, LocalDateTime.now().minusSeconds(Math.max(1L, safeRequest.getQueuedOlderThanSeconds())));
        }
        dataScopeSupport.applyQueueScope(wrapper, safeRequest, actorContext);
        if (includeOrdering) {
            wrapper.orderByDesc(Task::getAttentionRequired)
                    .orderByAsc(Task::getQueuedTime)
                    .orderByDesc(Task::getUpdateTime)
                    .orderByDesc(Task::getId);
        }
        return wrapper;
    }

    private void applyQueueStatusFilter(LambdaQueryWrapper<Task> wrapper, String status, boolean includeTerminal) {
        if (status != null) {
            wrapper.eq(Task::getStatus, status.toUpperCase(Locale.ROOT));
            return;
        }
        if (!includeTerminal) {
            wrapper.in(Task::getStatus, DEFAULT_OPERATIONAL_QUEUE_STATUSES);
        }
    }

    private int safeDeferCount(Task task) {
        return task.getDeferCount() == null ? 0 : Math.max(0, task.getDeferCount());
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
