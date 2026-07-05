/**
 * @Author : Cui
 * @Date: 2026/07/05 15:00
 * @Description DataSmart Govern Backend - SyncOfflineRunnerReportRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * 专用离线 Runner 低敏执行报告请求。
 *
 * <p>这个 DTO 面向后续 DataX-style 专用 Runner、Flink batch runner、Spark runner 或自研离线 worker。
 * 它不是给普通前端用户使用的表单，而是机器回调协议。与通用 checkpoint/complete/fail 回调相比，
 * 本请求把多个阶段统一成一份低敏报告：Runner 可以上报 QUEUED、RUNNING、CHECKPOINT、SUCCEEDED、FAILED 等状态，
 * data-sync 再把这些状态转换为已有 execution 生命周期动作。</p>
 *
 * <p>安全边界非常关键：该请求不允许承载 SQL 正文、statementRef 真实值、连接串、账号密码、对象映射原文、
 * 字段映射原文、过滤条件、分区条件、行样本或 checkpoint 原始业务水位。checkpoint 只能用
 * {@code checkpointRef}/{@code checkpointDigest} 这类引用或摘要表达。</p>
 */
@Data
public class SyncOfflineRunnerReportRequest {

    /**
     * 当前上报的执行器实例 ID。
     *
     * <p>它必须和 execution.executorId 匹配。这样即使其他 worker 知道 taskId/executionId，
     * 也不能伪造回调推进状态。</p>
     */
    @NotBlank(message = "执行器 ID 不能为空")
    private String executorId;

    /**
     * 专用 Runner 或 adapter 编码。
     *
     * <p>例如 DATAX_STANDALONE_RUNNER、FLINK_BATCH_RUNNER。该字段用于低基数诊断，不应拼接租户、表名、SQL 标识或机器名。</p>
     */
    private String adapterCode;

    /**
     * Runner 上报状态。
     *
     * <p>当前支持 QUEUED、RUNNING、PROGRESS、CHECKPOINT、SUCCEEDED、FAILED。
     * 服务层会按状态决定是只接受进度、写 checkpoint、标记成功，还是标记失败。</p>
     */
    @NotBlank(message = "Runner 状态不能为空")
    private String runnerStatus;

    /** 已读取记录数，必须是低敏计数，不能用它携带业务字段或样本。 */
    private Long recordsRead;

    /** 已写入记录数。 */
    private Long recordsWritten;

    /** 失败记录数。PROGRESS 阶段可用于运营诊断，FAILED 阶段可作为错误摘要依据。 */
    private Long failedRecordCount;

    /**
     * checkpoint 类型，例如 SCHEDULED_WINDOW、ID_RANGE_DIGEST、TIME_FIELD_DIGEST。
     *
     * <p>该字段描述水位类别，不承载真实水位值。</p>
     */
    private String checkpointType;

    /**
     * 低敏 checkpoint 引用。
     *
     * <p>可以是外部 runner 生成的 checkpoint 对象 ID、受控对象存储引用、加密水位引用或 data-sync 内部 checkpointRef。
     * 不允许把原始 ID、时间水位、binlog offset、Kafka offset 或 SQL 片段直接塞到这里。</p>
     */
    private String checkpointRef;

    /** checkpoint 摘要，用于证明水位内容一致，但不能还原原始水位。 */
    private String checkpointDigest;

    /** 分片或分区低敏标识。生产环境建议使用 runner shard id，而不是表名、分区表达式或日期窗口原文。 */
    private String shardOrPartition;

    /** 错误类型，FAILED 时使用，例如 CONNECTOR_ERROR、RUNNER_TIMEOUT、TARGET_WRITE_REJECTED。 */
    private String errorType;

    /** 低敏错误码。 */
    private String errorCode;

    /** 低敏错误摘要或错误 digest，不应放入异常堆栈、SQL、连接串或样本数据。 */
    private String errorMessage;

    /** 失败是否可重试。 */
    private Boolean retryable;

    /** 是否需要运营人员介入。当前先作为低敏报告事实保留，后续可触发 attention/incident。 */
    private Boolean operatorActionRequired;

    /** 低敏问题码集合，用于诊断和聚合统计。 */
    private List<String> issueCodes;

    /**
     * 幂等键。
     *
     * <p>同一个 Runner 报告因为网络重试重复投递时必须复用相同幂等键。终态回调会继续复用已有 lifecycle 幂等表。</p>
     */
    private String idempotencyKey;
}
