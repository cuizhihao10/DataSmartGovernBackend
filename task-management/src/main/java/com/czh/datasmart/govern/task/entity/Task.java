package com.czh.datasmart.govern.task.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 任务主表实体。
 * <p>
 * 这张表保存的是“当前快照”，也就是系统此刻如何看待这条任务：
 * 当前状态、当前进度、最新结果、当前可否继续重试等。
 * <p>
 * 它和 task_execution_log 的关系可以这样理解：
 * - Task：当前截面图。
 * - TaskExecutionLog：历史时间线。
 * <p>
 * 学习任务管理类系统时，理解这种“快照表 + 轨迹表”的建模方式非常重要。
 */
@Data
@TableName("task")
public class Task {

    /**
     * 主键 ID。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 任务名称。
     */
    private String name;

    /**
     * 任务描述。
     */
    private String description;

    /**
     * 任务类型。
     * 后续可以逐步收敛成更明确的业务枚举。
     */
    private String type;

    /**
     * 当前状态。
     */
    private String status;

    /**
     * 任务参数，当前先用 JSON 字符串保留灵活性。
     */
    private String params;

    /**
     * 当前执行进度。
     */
    private Integer progress;

    /**
     * 断点信息，用于未来断点续跑。
     */
    private String checkpoint;

    /**
     * 优先级。
     */
    private String priority;

    /**
     * 已经重试的次数。
     */
    private Integer retryCount;

    /**
     * 最大允许重试次数。
     */
    private Integer maxRetryCount;

    /**
     * 创建时间，由 MyBatis-Plus 在插入时自动填充。
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新时间，由 MyBatis-Plus 在插入和更新时维护。
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /**
     * 开始执行时间。
     */
    private LocalDateTime startTime;

    /**
     * 结束执行时间。
     */
    private LocalDateTime endTime;

    /**
     * 结果摘要或错误信息。
     */
    private String result;
}
