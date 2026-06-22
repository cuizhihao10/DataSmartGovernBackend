/**
 * @Author : Cui
 * @Date: 2026/06/22 10:31
 * @Description DataSmart Govern Backend - DataSyncWorkerExecutionReceipt.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * DataSync worker 执行回执实体。
 *
 * <p>这张表由 task-management 持有，用来保存 datasource-management Runner 的低敏执行事实。
 * 它不是同步数据明细表，也不是错误行存储表，而是任务中心用于还原端到端任务历史的“执行投影”。</p>
 *
 * <p>为什么它独立于 {@link DataSyncWorkerCommandOutbox}：</p>
 * <p>1. outbox 记录命令投递生命周期：PENDING、DISPATCHING、SUCCEEDED、DEAD_LETTER 等；</p>
 * <p>2. execution receipt 记录下游真实执行生命周期：PROGRESS、CHECKPOINT、COMPLETE、FAILED；</p>
 * <p>3. 一条 outbox 命令通常只会有一条下游接收 receipt，但一次 syncExecution 会产生多条执行进度回执；</p>
 * <p>4. 独立表可以按 commandId、syncTaskId/syncExecutionId、taskId、租户和时间建立索引，便于后续管理台查询和告警聚合。</p>
 *
 * <p>低敏边界：</p>
 * <p>本实体不保存 SQL、连接串、工具实参、样本数据、失败行、原始 checkpointValue、prompt、模型输出或内部 endpoint。
 * 如果下游传入 errorSummary/warnings，服务层会先做常见敏感片段脱敏和长度裁剪；API 视图默认仍不返回正文。</p>
 */
@Data
@TableName("task_data_sync_worker_execution_receipt")
public class DataSyncWorkerExecutionReceipt {

    /**
     * 数据库自增主键，仅用于表内排序和分页定位。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 执行回执幂等 ID。
     *
     * <p>datasource-management Runner 重试回写同一个事件时必须复用该值。
     * task-management 依赖数据库唯一键拒绝重复写入，避免进度时间线被重放污染。</p>
     */
    private String receiptId;

    /**
     * 关联的 commandId。
     *
     * <p>如果下游暂时无法携带 commandId，服务层会尝试通过 syncTaskId + syncExecutionId 回查 outbox，
     * 再把 commandId 补齐到本表。落库后该字段始终应该存在，便于从 Agent command 维度串联排障。</p>
     */
    private String commandId;

    /**
     * 关联的 task-management outbox 业务 ID。
     */
    private String outboxId;

    /**
     * task-management 主任务 ID。
     */
    private Long taskId;

    /**
     * Agent Runtime run/session/audit 引用。
     *
     * <p>这些字段来自 outbox 快照，而不是下游请求。这样做可以避免下游伪造上游上下文，
     * 也能保证 execution receipt 与最初工具命令保持一致。</p>
     */
    private String agentRunId;
    private String agentSessionId;
    private String auditId;

    /**
     * 租户、项目、工作空间隔离边界。
     */
    private Long tenantId;
    private Long projectId;
    private Long workspaceId;

    /**
     * datasource-management 内部同步任务与执行 ID。
     */
    private Long syncTaskId;
    private Long syncExecutionId;

    /**
     * 执行事件类型。
     */
    private String eventType;

    /**
     * 下游事件发生时间。
     *
     * <p>如果下游没有传，服务层使用 task-management 收到回执的时间。保留事件时间有助于后续分析
     * “下游执行慢”还是“回执链路慢”。</p>
     */
    private LocalDateTime eventTime;

    /**
     * 下游执行器 ID。
     *
     * <p>用于排查具体 Runner、分片 worker 或容器实例问题。它不是用户身份字段，不应作为权限判断依据。</p>
     */
    private String executorId;

    /**
     * 来源服务，当前默认 datasource-management。
     */
    private String sourceService;

    /**
     * 本批与累计计数字段。
     *
     * <p>这些字段只保存数量，不保存行内容。它们是任务进度、吞吐估算、失败率统计和容量告警的基础。</p>
     */
    private Long batchRecordsRead;
    private Long batchRecordsWritten;
    private Long batchFailedRecordCount;
    private Long totalRecordsRead;
    private Long totalRecordsWritten;
    private Long totalFailedRecordCount;

    /**
     * 进度百分比，允许为空。
     *
     * <p>很多增量/CDC/流式任务并没有天然的总量，因此不能强制要求百分比。为空表示只展示计数型进度。</p>
     */
    private Integer progressPercent;

    /**
     * 下游执行语义标记。
     */
    private Boolean endOfSource;
    private Boolean completed;
    private Boolean failed;
    private Boolean progressReported;
    private Boolean checkpointPersisted;

    /**
     * 检查点低敏描述。
     *
     * <p>checkpointType 可以是 PRIMARY_KEY、UPDATED_AT、OFFSET、PARTITION 等类型；
     * checkpointValueVisibility 只能是可见性策略，例如 HASHED、HIDDEN、WINDOW_ONLY，不能是原始值。</p>
     */
    private String checkpointType;
    private String checkpointValueVisibility;

    /**
     * 失败摘要与 warning 摘要。
     *
     * <p>这些字段即使经过脱敏，也默认不在 API 视图中返回正文。它们主要给受控审计后台或后续告警归因使用。</p>
     */
    private String errorSummary;
    private Integer warningCount;
    private String warningSummary;

    /**
     * 创建时间。
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新时间。
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
