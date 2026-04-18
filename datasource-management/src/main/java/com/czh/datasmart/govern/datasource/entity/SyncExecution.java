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
 * 每触发一次运行，都应该生成一条执行记录，而不是把所有统计直接覆盖回任务表。
 * 这样做可以保留完整运行历史，支持后续实现：
 * - 失败重试分析；
 * - 吞吐和成功率统计；
 * - 审计与故障复盘；
 * - 按执行编号回放和下载产物。
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
     * 同一任务下的第几次执行。
     * 这个字段比单纯依赖 create_time 更适合面向人类阅读和问题排查。
     */
    private Long executionNo;

    /**
     * 当前执行状态。
     * 与任务主状态相似，但更偏向单次运行视角。
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
     * 当前检查点引用。
     * 这里保留一个摘要型字段，方便列表快速浏览；
     * 真正可恢复的明细仍然存放在 sync_checkpoint 中。
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
     */
    private String errorSummary;

    /**
     * 触发执行的操作者 ID。
     */
    private Long triggeredBy;

    /**
     * 触发原因。
     * 例如手工运行、系统恢复、回放、补数等。
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
