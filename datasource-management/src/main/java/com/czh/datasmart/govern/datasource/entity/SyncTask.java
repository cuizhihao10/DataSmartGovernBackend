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
 * @Description DataSmart Govern Backend - SyncTask.java
 * @Version:1.0.0
 *
 * 同步任务实体。
 * 任务是模板在运营层面的具体化，它承载的是“一个可被人和系统管理的同步对象”，而不是某一次具体执行本身。
 *
 * 这里要特别区分三层概念：
 * 1. 数据源：回答“连接到哪里”。
 * 2. 模板：回答“按什么规则搬数据”。
 * 3. 任务：回答“谁负责、何时执行、当前处于什么管理状态”。
 *
 * 任务和执行记录分离非常重要，因为同一个任务可能会经历：
 * - 初次运行；
 * - 多次失败重试；
 * - 回放；
 * - 补数；
 * - 管理员强制干预。
 *
 * 如果把这些都直接压在任务表里，后续就很难追踪每次执行的历史差异。
 */
@Data
@TableName("sync_task")
public class SyncTask {

    /**
     * 任务主键。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 租户标识。
     * 用于后续做租户隔离、权限控制和资源配额管理。
     */
    private Long tenantId;

    /**
     * 关联模板 ID。
     * 说明这个任务是基于哪份同步模板实例化出来的。
     */
    private Long templateId;

    /**
     * 任务名称。
     * 会直接出现在任务列表、审批单据、运行看板和审计日志中。
     */
    private String name;

    /**
     * 任务说明。
     * 适合记录这次任务的业务场景、特殊要求或运行目标。
     */
    private String description;

    /**
     * 当前主状态。
     * 这是任务控制面最重要的状态字段，用来描述当前任务在生命周期中处于哪个阶段。
     */
    private String currentState;

    /**
     * 审批状态。
     * 它不是执行状态，而是治理状态，用来表达任务是否已经通过授权进入执行流程。
     */
    private String approvalState;

    /**
     * 优先级。
     * 后续会直接影响调度排序、资源倾斜和故障处置优先级。
     */
    private String priority;

    /**
     * 执行模式。
     * 用于表达这次任务是手工执行、计划执行、回放、补数还是恢复性运行。
     */
    private String runMode;

    /**
     * 触发类型。
     * 更偏向“这次启动是因为什么事件发生的”，适合用于审计和问题复盘。
     */
    private String triggerType;

    /**
     * 调度配置。
     * 当前阶段先保存为 JSON 字符串，后续如需支持复杂日历、时区和错峰策略，可拆成独立调度模型。
     */
    private String scheduleConfig;

    /**
     * 负责人 ID。
     * 用于明确任务运营责任归属。
     */
    private Long ownerId;

    /**
     * 最近一次执行记录 ID。
     * 便于任务列表快速定位最近一次运行，而不必每次都扫描执行历史。
     */
    private Long lastExecutionId;

    /**
     * 下一次计划运行时间。
     * 这是调度层面非常重要的摘要字段，适合直接用于列表展示和看板排序。
     */
    private LocalDateTime nextRunAt;

    /**
     * 最近一次进入队列的时间。
     * 这个字段是本轮新增的执行器语义基础字段，用来帮助平台判断：
     * - 任务已经在队列里等了多久；
     * - 是否出现队列积压；
     * - 调度器是否长时间没有分配执行器。
     */
    private LocalDateTime queuedAt;

    /**
     * 当前认领该任务的执行器实例标识。
     * 一旦任务被执行器认领，这个字段可以帮助平台快速定位“是哪台执行器拿走了这个任务”。
     */
    private String currentExecutorId;

    /**
     * 当前执行租约失效时间。
     * 这是任务层面的租约摘要字段，用来支撑执行器心跳、过期检测和后续失联恢复。
     */
    private LocalDateTime dispatchLeaseExpireAt;

    /**
     * 累计入队次数。
     * 这个字段不等于 retryCount，因为任务可能因为暂停恢复、调度重排、执行器重新认领而多次入队。
     */
    private Integer queueAttemptCount;

    /**
     * 是否启用任务。
     * 停用任务不会删除历史，而是阻止它继续进入新的调度或执行。
     */
    private Boolean enabled;

    /**
     * 是否需要人工关注。
     * 它不是主状态，而是给运维看板和告警系统看的辅助信号。
     */
    private Boolean operatorAttentionRequired;

    /**
     * 超时时间，单位秒。
     * 用于任务级临时覆盖，优先级通常高于模板默认配置。
     */
    private Integer timeoutSeconds;

    /**
     * 最大重试次数。
     * 用于限制自动或人工常规重试的上限，避免失控重试放大故障。
     */
    private Integer maxRetryCount;

    /**
     * 当前已重试次数。
     */
    private Integer retryCount;

    /**
     * 最近一次错误摘要。
     * 方便任务列表直接展示“最近为什么失败”。
     */
    private String latestErrorSummary;

    /**
     * 事件说明或人工处置备注。
     * 可沉淀审批意见、补数原因、故障说明和管理动作说明。
     */
    private String incidentNote;

    /**
     * 创建人。
     */
    private Long createdBy;

    /**
     * 更新人。
     */
    private Long updatedBy;

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
