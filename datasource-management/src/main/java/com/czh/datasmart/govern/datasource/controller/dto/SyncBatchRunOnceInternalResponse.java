/**
 * @Author : Cui
 * @Date: 2026/06/29 12:03
 * @Description DataSmart Govern Backend - SyncBatchRunOnceInternalResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import com.czh.datasmart.govern.datasource.service.execution.SyncDirtyRecordSample;

/**
 * 内部单批同步执行响应。
 *
 * <p>该响应是 connector runtime 返回给 data-sync 的低敏执行摘要。
 * 它回答“这一批有没有读到、有没有写入、是否建议上游回写 progress/checkpoint/complete/fail”，
 * 但不返回真实行数据、SQL、连接串、账号、密码、失败样本等数据面内容。</p>
 *
 * <p>checkpoint 候选值是一个例外，但它仍不是公开响应字段：该值只通过受服务账号鉴权保护的 internal 路由，
 * 从 datasource-management 交给 data-sync 控制面。data-sync 必须先把它写入 checkpoint 表，才能发起下一批读取；
 * 普通 Controller、Agent 上下文、WebSocket、审计摘要和日志都不得继续传播该原始值。</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SyncBatchRunOnceInternalResponse {

    /**
     * 固定载荷策略。
     */
    public static final String PAYLOAD_POLICY =
            "INTERNAL_RUN_ONCE_RESULT_CHECKPOINT_VALUE_RESTRICTED_NO_ROWS_NO_SQL_NO_CREDENTIALS";

    /**
     * 同步任务 ID。
     */
    private Long taskId;

    /**
     * execution ID。
     */
    private Long executionId;

    /**
     * 本批执行状态。
     *
     * <p>典型值包括 BATCH_WRITTEN_MORE_REMAIN、SOURCE_EXHAUSTED_COMPLETE_REQUIRED、READ_FAILED、
     * WRITE_FAILED、RUNNER_FAILED。</p>
     */
    private String runStatus;

    /**
     * 本批读取记录数。
     */
    private Long batchRecordsRead;

    /**
     * 本批写入记录数。
     */
    private Long batchRecordsWritten;

    /**
     * 本批失败记录数。
     */
    private Long batchFailedRecordCount;

    /**
     * 加上本批后的累计读取记录数。
     */
    private Long totalRecordsRead;

    /**
     * 加上本批后的累计写入记录数。
     */
    private Long totalRecordsWritten;

    /**
     * 加上本批后的累计失败记录数。
     */
    private Long totalFailedRecordCount;

    /**
     * 是否读到源端结尾。
     */
    private Boolean endOfSource;

    /**
     * 本批是否失败。
     */
    private Boolean failed;

    /**
     * 是否建议 data-sync 回写 progress。
     *
     * <p>run-once 不直接修改 data-sync 状态；该字段只告诉上游“已经有可回写的低敏进度事实”。</p>
     */
    private Boolean progressCallbackRecommended;

    /**
     * 是否建议 data-sync 回写 checkpoint。
     *
     * <p>true 表示本批 reader 产生了下一水位候选值，data-sync 必须先持久化再读取下一批。</p>
     */
    private Boolean checkpointCallbackRecommended;

    /**
     * 是否产生了 checkpoint 候选值。
     */
    private Boolean checkpointCandidateProduced;

    /**
     * 本批 reader 产生的下一 checkpoint 原始值。
     *
     * <p>该字段只属于 datasource-management -> data-sync 的内部执行合同。它可能包含业务时间、递增 ID、
     * 分区窗口或查询边界，因此禁止写入日志、公开回执、审计详情、Agent 提示词或用户侧 API。
     * data-sync 收到后必须先校验 checkpoint 类型和执行范围，再做幂等持久化。</p>
     */
    private Object checkpointCandidateValue;

    /**
     * checkpoint 交接策略。
     *
     * <p>当前使用 internal 服务响应交接，并要求 data-sync 在下一批前完成持久化。</p>
     */
    private String checkpointHandoffMode;

    /**
     * 是否建议 data-sync 调用 complete 回调。
     */
    private Boolean completeCallbackRecommended;

    /**
     * 是否建议 data-sync 调用 fail 回调。
     */
    private Boolean failCallbackRecommended;

    /**
     * checkpoint 类型，例如 TIME_WATERMARK、ID_WATERMARK。
     */
    private String checkpointType;

    /**
     * checkpoint 值可见性说明。
     */
    private String checkpointValueVisibility;

    /**
     * 低敏错误摘要。
     */
    private String errorSummary;

    /**
     * 结构化脏数据样本。
     *
     * <p>只有在 writer 已经做过行级隔离时才可能返回。样本不包含完整原始行，只包含低敏定位和错误分类，
     * data-sync 会把它写入错误样本表，供权限受控查询和后续修复重放。</p>
     */
    private List<SyncDirtyRecordSample> dirtySamples;

    /**
     * 本批是否超过脏数据阈值。
     */
    private Boolean dirtyThresholdExceeded;

    /**
     * 执行准备阶段继承的低敏警告。
     */
    private List<String> warnings;

    /**
     * 载荷策略。
     */
    private String payloadPolicy;
}
