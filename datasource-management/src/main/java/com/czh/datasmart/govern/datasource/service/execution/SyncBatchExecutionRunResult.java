/**
 * @Author : Cui
 * @Date: 2026/06/20 16:25
 * @Description DataSmart Govern Backend - SyncBatchExecutionRunResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.execution;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 单批同步执行结果。
 *
 * <p>该结果是 Runner 返回给内部 worker 调度层的低敏摘要。
 * 它只说明本批读写了多少、是否完成、是否失败、是否回写 progress/checkpoint，
 * 不包含真实行数据、SQL 模板、连接信息、账号密码、样本值或 checkpoint 原始值。</p>
 *
 * <p>为什么不把 `nextCheckpointValue` 暴露在结果里：</p>
 * <p>1. checkpoint 值可能是业务时间、水位 ID、分区游标，本身可能透露业务节奏或数据范围；</p>
 * <p>2. 当前已有 `SyncTaskService.reportProgress` 负责把 checkpoint 写入受控表；</p>
 * <p>3. Runner 调用方只需要知道 checkpoint 是否已尝试持久化，以及该值的可见性边界。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SyncBatchExecutionRunResult {

    /**
     * 同步任务 ID。
     * 用于外层 worker 日志、outbox receipt 和后续任务台关联。
     */
    private Long taskId;

    /**
     * 本次 execution ID。
     * 一个任务可以多次执行，每次执行都应独立记录进度和结果。
     */
    private Long executionId;

    /**
     * 本批读取记录数。
     * 这是本次 Runner 调用的增量统计，不等于 execution 的累计统计。
     */
    private Long batchRecordsRead;

    /**
     * 本批成功写入记录数。
     */
    private Long batchRecordsWritten;

    /**
     * 本批失败记录数。
     */
    private Long batchFailedRecordCount;

    /**
     * execution 累计读取记录数。
     * Runner 回写 progress/complete 时使用的是累计值，便于任务看板展示总进度。
     */
    private Long totalRecordsRead;

    /**
     * execution 累计写入记录数。
     */
    private Long totalRecordsWritten;

    /**
     * execution 累计失败记录数。
     */
    private Long totalFailedRecordCount;

    /**
     * 是否已经读到源端结尾。
     * 如果为 true，Runner 会调用 completeExecution；如果为 false，只回写 progress，等待外层循环继续调度下一批。
     */
    private Boolean endOfSource;

    /**
     * 本批是否已经把 execution 推进到完成态。
     * 注意完成态可能是 SUCCEEDED，也可能因为失败记录数大于 0 而变为 PARTIALLY_SUCCEEDED。
     */
    private Boolean completed;

    /**
     * 本批是否已经把 execution 推进到失败态。
     */
    private Boolean failed;

    /**
     * 是否已经调用 progress 回写。
     */
    private Boolean progressReported;

    /**
     * 是否在 progress 回写中携带了 checkpoint。
     */
    private Boolean checkpointPersisted;

    /**
     * checkpoint 类型，例如 TIME_WATERMARK、ID_WATERMARK。
     */
    private String checkpointType;

    /**
     * checkpoint 值可见性说明。
     * 该字段来自执行计划，提醒调用方 checkpoint 原始值只能留在 worker 内部和 checkpoint 表。
     */
    private String checkpointValueVisibility;

    /**
     * 低敏错误摘要。
     * 这里保存的是错误类型、阶段和简短说明，不包含 SQL、连接串、样本行、密码或其他敏感上下文。
     */
    private String errorSummary;

    /**
     * 执行准备阶段继承下来的低敏警告。
     */
    private List<String> warnings;
}
