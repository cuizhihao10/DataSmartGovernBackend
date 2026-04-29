package com.czh.datasmart.govern.task.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * @Author : Cui
 * @Date: 2026/04/27 01:05
 * @Description DataSmart Govern Backend - TaskExecutionRun.java
 * @Version:1.0.0
 *
 * 任务执行记录实体。
 *
 * <p>Task 主表保存“任务当前态”，TaskExecutionLog 保存“发生过哪些事件”，
 * 而 TaskExecutionRun 保存“每一次执行尝试”。
 *
 * <p>为什么要单独建执行记录：
 * 1. 一个任务可能失败后重试多次，每次执行都应该有独立 runNo、执行器、开始结束时间、心跳和结果；
 * 2. 调度器需要知道当前哪台执行器认领了任务，以及租约什么时候过期；
 * 3. 运营后台需要查看执行历史，而不是只看最新状态；
 * 4. 后续做 replay、backfill、选择性重试、执行产物查询时，run 是比 task 更自然的聚合粒度。
 */
@Data
@TableName("task_execution_run")
public class TaskExecutionRun {

    /**
     * 执行记录主键。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 所属任务 ID。
     */
    private Long taskId;

    /**
     * 同一任务下第几次执行。
     *
     * <p>第一次认领为 1，失败重试后再次认领为 2，以此类推。
     */
    private Long runNo;

    /**
     * 执行器实例 ID。
     *
     * <p>真实部署中可以是 worker 名称、Pod 名称、机器 ID 或 Agent Runtime 实例 ID。
     */
    private String executorId;

    /**
     * 执行状态，例如 RUNNING、SUCCESS、FAILED、TIMEOUT、CANCELLED。
     */
    private String state;

    /**
     * 触发类型，例如 EXECUTOR_CLAIM、MANUAL_RETRY、SCHEDULED。
     */
    private String triggerType;

    /**
     * 触发人或触发主体 ID。
     *
     * <p>服务账号、调度器和人类用户都可以作为触发主体。
     */
    private Long triggeredBy;

    /**
     * 开始执行时间。
     */
    private LocalDateTime startedAt;

    /**
     * 结束时间。
     */
    private LocalDateTime finishedAt;

    /**
     * 最近一次心跳时间。
     */
    private LocalDateTime heartbeatAt;

    /**
     * 当前租约过期时间。
     *
     * <p>如果执行器超过该时间没有续租，调度器或运维接口可以认为该执行器失联。
     */
    private LocalDateTime leaseExpireTime;

    /**
     * 执行进度。
     */
    private Integer progress;

    /**
     * 检查点。
     *
     * <p>执行器心跳时可以上报 checkpoint，后续失败恢复或断点续跑可以复用。
     */
    private String checkpoint;

    /**
     * 错误摘要。
     */
    private String errorMessage;

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
