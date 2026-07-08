/**
 * @Author : Cui
 * @Date: 2026/07/09 18:54
 * @Description DataSmart Govern Backend - SyncExecutionLogSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncExecutionLog;
import com.czh.datasmart.govern.datasync.entity.SyncObjectExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;

/**
 * 同步执行日志落库支撑组件。
 *
 * <p>本组件专门负责把“执行过程中发生的关键阶段”转换成可查询、可展示、可审计的结构化日志。
 * 它不改变 execution/task/objectExecution 的业务状态，因此不会替代生命周期服务；
 * 它只在状态变化或关键运行阶段旁边记录“刚刚发生了什么”。</p>
 *
 * <p>为什么单独拆出这个类：</p>
 * <p>1. 生命周期服务关注状态机正确性，例如 QUEUED -> RUNNING -> SUCCEEDED；</p>
 * <p>2. run-once 派发服务关注远端 Reader/Writer 调用和失败裁决；</p>
 * <p>3. 对象/分片服务关注工作单元账本；</p>
 * <p>4. 运行日志横跨这些位置，如果把落库字段拼装散落在各个 Impl 中，后续字段、低敏策略或速度算法调整会非常难维护。</p>
 *
 * <p>低敏策略：调用方只能传入短消息、低敏摘要和对象/分片标识。方法内部不会接收 SQL、连接串、凭据或行样本参数。
 * 如果未来确实需要保存更详细的诊断，应先设计脱敏策略和权限分级，而不是直接扩展 message。</p>
 */
@Component
@RequiredArgsConstructor
public class SyncExecutionLogSupport {

    /**
     * 运行日志安全策略常量。
     *
     * <p>该字段会落到每条日志中，便于后续导出、API 审计或 Agent 消费时再次确认：
     * 这是一条低敏产品日志，不包含 SQL、凭据、连接串、样本行或真实分片边界。</p>
     */
    public static final String PAYLOAD_POLICY =
            "LOW_SENSITIVE_EXECUTION_LOG_NO_SQL_NO_CREDENTIALS_NO_ROWS_NO_RAW_RANGE";

    private static final int MAX_MESSAGE_LENGTH = 500;
    private static final int MAX_DETAIL_LENGTH = 2000;

    private final SyncExecutionLogMapper executionLogMapper;

    /**
     * 记录 execution 级日志。
     *
     * <p>适用于入队、认领、计划生成、远端调用开始、checkpoint、完成和失败这类父 execution 事件。</p>
     *
     * @param task 当前同步任务，用于补齐租户、项目和任务 ID。
     * @param execution 当前执行记录，用于补齐 executionId、执行器、累计计数和速度估算。
     * @param actorContext 当前操作者或服务账号上下文，用于记录 traceId。
     * @param stage 阶段分类，例如 QUEUE、CLAIM、PLAN、REMOTE_BATCH、COMPLETE。
     * @param level 日志级别，INFO/WARN/ERROR。
     * @param eventType 细粒度事件类型。
     * @param status 事件结果。
     * @param message 面向用户的中文短消息。
     * @param detailSummary 低敏详情摘要。
     */
    public void recordExecutionEvent(SyncTask task,
                                     SyncExecution execution,
                                     SyncActorContext actorContext,
                                     String stage,
                                     String level,
                                     String eventType,
                                     String status,
                                     String message,
                                     String detailSummary) {
        recordExecutionEvent(task, execution, actorContext, stage, level, eventType, status, message,
                detailSummary, ExecutionMetricSnapshot.fromExecution(execution));
    }

    /**
     * 记录带自定义计数快照的 execution 级日志。
     *
     * <p>某些阶段的计数来自远端响应或对象/分片汇总，而不是 execution 当前表字段。
     * 例如 run-once 第 2 批返回了累计写入 1024 行，但 complete 还没有回写 execution，
     * 此时调用方可以传入 {@link ExecutionMetricSnapshot}，让运行历史页面实时看到进度。</p>
     */
    public void recordExecutionEvent(SyncTask task,
                                     SyncExecution execution,
                                     SyncActorContext actorContext,
                                     String stage,
                                     String level,
                                     String eventType,
                                     String status,
                                     String message,
                                     String detailSummary,
                                     ExecutionMetricSnapshot metrics) {
        if (task == null || execution == null) {
            return;
        }
        SyncExecutionLog log = baseLog(task, execution, actorContext, metrics);
        log.setLogStage(normalizeCode(stage, "GENERAL"));
        log.setLogLevel(normalizeCode(level, "INFO"));
        log.setEventType(normalizeCode(eventType, "EXECUTION_EVENT"));
        log.setEventStatus(normalizeCode(status, "PROGRESS"));
        log.setMessage(truncate(message, MAX_MESSAGE_LENGTH));
        log.setDetailSummary(truncate(detailSummary, MAX_DETAIL_LENGTH));
        executionLogMapper.insert(log);
    }

    /**
     * 记录对象或分片级日志。
     *
     * <p>对象/分片日志会额外写入 objectExecutionId、objectOrdinal、workUnitType 和 shardOrPartition。
     * 这些字段让前端能把日志和“对象/分片”页签中的账本行关联起来，用户不需要在一堆纯文本日志中猜是哪张表失败。</p>
     */
    public void recordObjectEvent(SyncTask task,
                                  SyncExecution execution,
                                  SyncObjectExecution objectExecution,
                                  SyncActorContext actorContext,
                                  String stage,
                                  String level,
                                  String eventType,
                                  String status,
                                  String message,
                                  String detailSummary,
                                  ExecutionMetricSnapshot metrics) {
        if (task == null || execution == null || objectExecution == null) {
            return;
        }
        SyncExecutionLog log = baseLog(task, execution, actorContext,
                metrics == null ? ExecutionMetricSnapshot.fromObject(objectExecution) : metrics);
        log.setLogStage(normalizeCode(stage, "OBJECT"));
        log.setLogLevel(normalizeCode(level, "INFO"));
        log.setEventType(normalizeCode(eventType, "OBJECT_EVENT"));
        log.setEventStatus(normalizeCode(status, "PROGRESS"));
        log.setMessage(truncate(message, MAX_MESSAGE_LENGTH));
        log.setDetailSummary(truncate(detailSummary, MAX_DETAIL_LENGTH));
        log.setWorkUnitType(objectExecution.getWorkUnitType());
        log.setObjectExecutionId(objectExecution.getId());
        log.setObjectOrdinal(objectExecution.getObjectOrdinal());
        log.setShardOrPartition(objectExecution.getShardOrPartition());
        executionLogMapper.insert(log);
    }

    /**
     * 构造日志公共字段。
     *
     * <p>所有日志都从 task/execution 继承租户、项目、任务、执行器和基础计数，保证查询过滤与权限收口稳定。
     * 速度在这里统一计算，避免每个调用点出现不同算法。</p>
     */
    private SyncExecutionLog baseLog(SyncTask task,
                                     SyncExecution execution,
                                     SyncActorContext actorContext,
                                     ExecutionMetricSnapshot metrics) {
        LocalDateTime now = LocalDateTime.now();
        ExecutionMetricSnapshot safeMetrics = metrics == null
                ? ExecutionMetricSnapshot.fromExecution(execution)
                : metrics;
        SyncExecutionLog log = new SyncExecutionLog();
        log.setTenantId(task.getTenantId());
        log.setProjectId(task.getProjectId());
        log.setWorkspaceId(task.getWorkspaceId());
        log.setSyncTaskId(task.getId());
        log.setExecutionId(execution.getId());
        log.setExecutorId(execution.getExecutorId());
        log.setRecordsRead(zeroIfNull(safeMetrics.recordsRead()));
        log.setRecordsWritten(zeroIfNull(safeMetrics.recordsWritten()));
        log.setFailedRecordCount(zeroIfNull(safeMetrics.failedRecordCount()));
        log.setCompletedWorkUnits(safeMetrics.completedWorkUnits());
        log.setSucceededWorkUnits(safeMetrics.succeededWorkUnits());
        log.setFailedWorkUnits(safeMetrics.failedWorkUnits());
        log.setProgressPercent(safeMetrics.progressPercent());
        log.setSpeedRowsPerSecond(speedRowsPerSecond(execution, safeMetrics.recordsWritten(), now));
        log.setEventTime(now);
        log.setTraceId(actorContext == null ? null : actorContext.traceId());
        log.setPayloadPolicy(PAYLOAD_POLICY);
        log.setCreateTime(now);
        return log;
    }

    /**
     * 估算当前写入速度。
     *
     * <p>这里用累计写入行数 / 已运行秒数计算。它不是底层 JDBC 的精确瞬时吞吐，但足以支撑运行历史页面展示：
     * “已经写了多少、速度大概是多少、是不是长时间没有推进”。如果后续引入 SSE/WebSocket 或专用 Runner，
     * 可以再增加滑动窗口速度，不需要修改日志表结构。</p>
     */
    private BigDecimal speedRowsPerSecond(SyncExecution execution, Long recordsWritten, LocalDateTime eventTime) {
        LocalDateTime baseline = execution.getStartedAt() != null
                ? execution.getStartedAt()
                : execution.getQueuedAt();
        if (baseline == null || recordsWritten == null || recordsWritten <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        long seconds = Math.max(1L, Duration.between(baseline, eventTime).getSeconds());
        return BigDecimal.valueOf(recordsWritten)
                .divide(BigDecimal.valueOf(seconds), 2, RoundingMode.HALF_UP);
    }

    private Long zeroIfNull(Long value) {
        return value == null ? 0L : value;
    }

    private String normalizeCode(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    /**
     * 执行日志计数快照。
     *
     * <p>快照用于把“某个阶段当时看到的计数”保存下来，而不是强制依赖 execution 表当前值。
     * 这对运行中展示非常重要：批次完成、分片完成、对象完成时，execution 可能还没有进入终态，
     * 但用户已经应该看到最新读取/写入/失败数量。</p>
     */
    public record ExecutionMetricSnapshot(
            Long recordsRead,
            Long recordsWritten,
            Long failedRecordCount,
            Integer completedWorkUnits,
            Integer succeededWorkUnits,
            Integer failedWorkUnits,
            BigDecimal progressPercent
    ) {

        public static ExecutionMetricSnapshot fromExecution(SyncExecution execution) {
            if (execution == null) {
                return empty();
            }
            return new ExecutionMetricSnapshot(
                    execution.getRecordsRead(),
                    execution.getRecordsWritten(),
                    execution.getFailedRecordCount(),
                    null,
                    null,
                    null,
                    null
            );
        }

        public static ExecutionMetricSnapshot fromObject(SyncObjectExecution objectExecution) {
            if (objectExecution == null) {
                return empty();
            }
            return new ExecutionMetricSnapshot(
                    objectExecution.getRecordsRead(),
                    objectExecution.getRecordsWritten(),
                    objectExecution.getFailedRecordCount(),
                    null,
                    null,
                    null,
                    null
            );
        }

        public static ExecutionMetricSnapshot of(Long recordsRead,
                                                 Long recordsWritten,
                                                 Long failedRecordCount,
                                                 Integer completedWorkUnits,
                                                 Integer succeededWorkUnits,
                                                 Integer failedWorkUnits,
                                                 BigDecimal progressPercent) {
            return new ExecutionMetricSnapshot(recordsRead, recordsWritten, failedRecordCount,
                    completedWorkUnits, succeededWorkUnits, failedWorkUnits, progressPercent);
        }

        public static ExecutionMetricSnapshot empty() {
            return new ExecutionMetricSnapshot(0L, 0L, 0L, null, null, null, null);
        }
    }
}
