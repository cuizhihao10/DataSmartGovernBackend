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
 * @Date: 2026/4/18 22:14
 * @Description DataSmart Govern Backend - TaskExecutionLog.java
 * @Version:1.0.0
 *
 * 任务执行日志实体。
 * 如果说 Task 表回答的是“任务现在怎么样了”，
 * 那么这张表回答的就是“任务为什么会变成现在这样”。
 *
 * 它的存在很重要，因为很多真实问题都不能只看最终状态：
 * - 为什么失败？
 * - 中间暂停过没有？
 * - 是否发生过重试？
 * - 是人工取消还是系统自动动作？
 *
 * 因此这张表是任务中心后续做监控、审计、回放和故障复盘的基础。
 */
@Data
@TableName("task_execution_log")
public class TaskExecutionLog {

    /**
     * 日志主键。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 所属任务 ID。
     * 用于把多条状态事件聚合到同一条任务主线上。
     */
    private Long taskId;

    /**
     * 动作名称。
     * 例如 CREATE、START、PAUSE、RESUME、RETRY、FAIL。
     * 它回答的是“发生了什么动作”，不完全等价于状态本身。
     */
    private String action;

    /**
     * 变更前状态。
     * 如果是创建场景，可能为空。
     */
    private String fromStatus;

    /**
     * 变更后状态。
     * 某些日志（如 PROGRESS）前后状态可能相同，因为它记录的是过程更新而不是状态跳转。
     */
    private String toStatus;

    /**
     * 简短摘要消息。
     * 适合列表直接展示，让调用方快速知道本次事件的大意。
     */
    private String message;

    /**
     * 操作主体。
     * 当前阶段统一记录为 system，后续可以扩展为 user、scheduler、agent、gateway 等。
     */
    private String operator;

    /**
     * 详细上下文。
     * 用于记录 checkpoint、失败原因、重试次数、执行参数等细节。
     */
    private String details;

    /**
     * 日志创建时间。
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
