/**
 * @Author : Cui
 * @Date: 2026/06/27 02:28
 * @Description DataSmart Govern Backend - SyncExecutionRecoveryPlan.java
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
 * 同步执行恢复计划实体。
 *
 * <p>replay/backfill 不能只创建一条 QUEUED execution，否则 worker 只知道“要跑一次”，不知道“从哪里回放、补哪个窗口”。
 * 本表把恢复类 execution 的低敏计划独立保存，避免把回放窗口、来源 checkpoint 等语义塞进 execution.errorSummary 这类不合适字段。
 *
 * <p>当前计划表只保存控制面所需的低敏事实：
 * 1. 来源 execution 与 checkpoint；
 * 2. 补数窗口或分区选择器；
 * 3. 操作原因摘要；
 * 4. 计划状态。
 *
 * <p>它不保存 SQL、连接串、密码、token、样本数据、源端结果、目标端响应、模型输出或工具原始参数。
 */
@Data
@TableName("data_sync_execution_recovery_plan")
public class SyncExecutionRecoveryPlan {

    /** 恢复计划主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 租户 ID，冗余自任务，用于租户级隔离与后续清理。 */
    private Long tenantId;

    /** 项目 ID，冗余自任务，用于 PROJECT 数据范围与项目级审计。 */
    private Long projectId;

    /** 工作空间 ID，冗余自任务，用于空间级运行视图。 */
    private Long workspaceId;

    /** 所属同步任务 ID。 */
    private Long syncTaskId;

    /** 本计划驱动的新 executionId。 */
    private Long executionId;

    /** 恢复类型：REPLAY 或 BACKFILL。 */
    private String recoveryType;

    /** replay 来源 executionId；backfill 可为空。 */
    private Long sourceExecutionId;

    /** replay 来源 checkpointId；如果为空，表示从来源 execution 起点回放。 */
    private Long sourceCheckpointId;

    /** backfill 窗口开始边界，低敏字符串。 */
    private String windowStart;

    /** backfill 窗口结束边界，低敏字符串。 */
    private String windowEnd;

    /** backfill 分片或分区选择器。 */
    private String shardOrPartition;

    /** 低敏操作原因，禁止保存 SQL、样本、凭据或连接串。 */
    private String reason;

    /** 计划状态：CREATED、CLAIMED、CONSUMED、CANCELLED。当前阶段先写 CREATED。 */
    private String planState;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
