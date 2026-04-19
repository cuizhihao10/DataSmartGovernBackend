package com.czh.datasmart.govern.datasource.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * @Author : Cui
 * @Date: 2026/4/18 23:12
 * @Description DataSmart Govern Backend - SyncExecution.java
 * @Version:1.0.0
 *
 * 同步执行记录实体。
 * 每触发一次运行，都应该生成一条独立执行记录，而不是把统计信息直接覆盖回任务表。
 *
 * 执行记录独立建模的价值在于：
 * 1. 可以完整保留历史运行轨迹。
 * 2. 可以支撑失败重试分析、吞吐统计和 SLA 计算。
 * 3. 可以把检查点、错误样本、运行日志都挂到具体的一次执行上。
 */
@Data
@TableName("sync_execution")
public class SyncExecution {

    /**
     * 执行记录主键。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 所属同步任务 ID。
     */
    private Long syncTaskId;

    /**
     * 在同一任务下的第几次执行。
     * 这个字段比单纯依赖 create_time 更适合人类阅读和排查问题。
     */
    private Long executionNo;

    /**
     * 当前执行状态。
     * 它与任务主状态类似，但更偏向“某一次运行实例当前处于什么阶段”。
     */
    private String state;

    /**
     * 开始执行时间。
     */
    private LocalDateTime startedAt;

    /**
     * 完成或结束时间。
     */
    private LocalDateTime finishedAt;

    /**
     * 当前检查点引用摘要。
     * 便于在列表页快速看到任务大概跑到哪里，真正的明细仍保存在 sync_checkpoint 中。
     */
    private String checkpointRef;

    /**
     * 已读取记录数。
     */
    private Long recordsRead;

    /**
     * 已写入记录数。
     */
    private Long recordsWritten;

    /**
     * 失败记录数。
     */
    private Long failedRecordCount;

    /**
     * 错误摘要。
     * 当前只保留摘要信息，后续如要支持错误样本中心，可再拆出专门错误表。
     */
    private String errorSummary;

    /**
     * 触发执行的操作者 ID。
     */
    private Long triggeredBy;

    /**
     * 当前执行器实例标识。
     * 当前阶段先记录为字符串，便于兼容将来的容器实例 ID、主机名、节点名或服务账户标识。
     */
    private String executorId;

    /**
     * 最近一次心跳时间。
     * 它主要服务于执行器保活和失联检测，而不是普通用户界面展示。
     */
    private LocalDateTime heartbeatAt;

    /**
     * 执行租约失效时间。
     * 平台可以借助该字段判断某次执行是否已经长时间无人续租，从而为后续的失联恢复预留依据。
     */
    private LocalDateTime leaseExpireAt;

    /**
     * 触发原因。
     * 例如手工执行、系统恢复、回放、补数等。
     */
    private String triggerReason;

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
