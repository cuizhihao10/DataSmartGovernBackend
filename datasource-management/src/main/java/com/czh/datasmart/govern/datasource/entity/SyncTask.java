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
 * 任务是模板在运营层面的具体化，它承载：
 * - 当前处于什么生命周期状态；
 * - 是否需要审批；
 * - 谁负责；
 * - 什么时候执行；
 * - 最近一次执行到哪里、为什么失败。
 *
 * 设计上要特别注意把“任务定义”和“执行历史”分开：
 * - 本表描述的是任务本身的管理状态；
 * - 具体每次运行的读写量、错误数、开始结束时间，放到 sync_execution 中。
 * 这样能避免一个任务多次运行后历史被覆盖。
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
     */
    private Long tenantId;

    /**
     * 引用的同步模板 ID。
     */
    private Long templateId;

    /**
     * 任务名称。
     */
    private String name;

    /**
     * 任务说明。
     */
    private String description;

    /**
     * 当前主状态。
     */
    private String currentState;

    /**
     * 审批状态。
     */
    private String approvalState;

    /**
     * 优先级。
     */
    private String priority;

    /**
     * 执行模式。
     */
    private String runMode;

    /**
     * 触发类型。
     */
    private String triggerType;

    /**
     * 调度配置。
     * 当前先使用 JSON 字符串，后续如果调度策略复杂，可拆成独立调度表。
     */
    private String scheduleConfig;

    /**
     * 负责人 ID。
     */
    private Long ownerId;

    /**
     * 最近一次执行记录 ID。
     */
    private Long lastExecutionId;

    /**
     * 下一次计划运行时间。
     */
    private LocalDateTime nextRunAt;

    /**
     * 是否启用。
     */
    private Boolean enabled;

    /**
     * 是否需要人工关注。
     * 它不是主状态，而是对运维看板很有价值的辅助信号。
     */
    private Boolean operatorAttentionRequired;

    /**
     * 超时时间，单位秒。
     * 这里作为任务层面的显式覆盖值，便于管理员做单任务临时调整。
     */
    private Integer timeoutSeconds;

    /**
     * 最大重试次数。
     */
    private Integer maxRetryCount;

    /**
     * 当前已重试次数。
     */
    private Integer retryCount;

    /**
     * 最近一次错误摘要。
     */
    private String latestErrorSummary;

    /**
     * 事件说明或人工处置备注。
     * 例如审批意见、补数原因、故障处置说明都可以沉淀在这里。
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
