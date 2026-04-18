package com.czh.datasmart.govern.task.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 任务执行日志实体。
 * <p>
 * 这张表专门用来回答“这个任务经历过什么”。
 * 对学习和运维都很有价值，因为很多时候问题不在于最终状态本身，
 * 而在于状态是通过哪一连串动作变化过来的。
 */
@Data
@TableName("task_execution_log")
public class TaskExecutionLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 关联任务 ID。
     */
    private Long taskId;

    /**
     * 动作名称，例如 CREATE、START、RETRY、FAIL。
     */
    private String action;

    /**
     * 变更前状态。
     */
    private String fromStatus;

    /**
     * 变更后状态。
     */
    private String toStatus;

    /**
     * 日志摘要，适合列表直接展示。
     */
    private String message;

    /**
     * 操作主体。
     * 当前固定为 system，后续可拓展为 user、scheduler、agent 等。
     */
    private String operator;

    /**
     * 详细上下文，例如错误原因、checkpoint、重试计数等。
     */
    private String details;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
