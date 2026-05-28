/**
 * @Author : Cui
 * @Date: 2026/05/07 21:38
 * @Description DataSmart Govern Backend - SyncExecution.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 同步执行记录实体。
 *
 * <p>一条 SyncTask 可以运行很多次，每一次运行都应该有独立执行记录。
 * 执行记录是后续生产化能力的中心锚点：checkpoint、错误样本、吞吐指标、执行器租约、审计事件都会关联到 executionId。
 */
@Data
@TableName("data_sync_execution")
public class SyncExecution {

    /** 执行记录主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 租户 ID，冗余保存是为了执行历史查询不必回表任务主表。 */
    private Long tenantId;

    /**
     * 项目 ID。
     *
     * <p>执行记录冗余项目 ID，是为了后续提供“项目级执行历史”“项目级失败率”和“项目级成本分析”时不必频繁 join 任务表。
     */
    private Long projectId;

    /**
     * 工作空间 ID。
     */
    private Long workspaceId;

    /** 关联同步任务 ID。 */
    private Long syncTaskId;

    /** 同一任务下的第几次执行，用于用户理解“第 N 次运行”。 */
    private Long executionNo;

    /** 执行状态，例如 QUEUED、RUNNING、SUCCEEDED、FAILED。 */
    private String executionState;

    /** 触发方式，例如 MANUAL、SCHEDULED、BACKFILL、REPLAY。 */
    private String triggerType;

    /** 入队时间。第一版 runTask 会先创建 QUEUED 执行记录。 */
    private LocalDateTime queuedAt;

    /** 真正开始执行时间，执行器认领后再写入。 */
    private LocalDateTime startedAt;

    /** 执行完成时间，成功、失败或取消时写入。 */
    private LocalDateTime finishedAt;

    /** 最近 checkpoint 引用，后续可保存 checkpoint 表主键或外部存储引用。 */
    private String checkpointRef;

    /** 已读取记录数。 */
    private Long recordsRead;

    /** 已写入记录数。 */
    private Long recordsWritten;

    /** 失败记录数。 */
    private Long failedRecordCount;

    /** 错误摘要，用于列表快速展示，不替代错误样本表。 */
    private String errorSummary;

    /** 触发人 ID。 */
    private Long triggeredBy;

    /** 执行器实例 ID，后续 worker 认领后写入。 */
    private String executorId;

    /**
     * 最近一次执行器心跳时间。
     *
     * <p>心跳用于判断执行器是否仍然存活。后续如果超过 leaseExpireTime 仍没有心跳，
     * 平台可以把 execution 重新放回队列或转入需要人工关注状态。
     */
    private LocalDateTime heartbeatTime;

    /**
     * 当前执行租约过期时间。
     *
     * <p>租约是执行器并发安全的基础：只有持有租约的 executorId 才能继续写 checkpoint、complete 或 fail。
     */
    private LocalDateTime leaseExpireTime;

    /**
     * 当前 execution 被延迟回队列的次数。
     *
     * <p>defer 不是业务失败，而是执行器因为容量、配额、维护窗口等原因主动退避。
     * 单独计数便于后续发现“总是被退避”的任务，触发运营关注或告警。
     */
    private Integer deferCount;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
