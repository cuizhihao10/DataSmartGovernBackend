/**
 * @Author : Cui
 * @Date: 2026/05/07 21:27
 * @Description DataSmart Govern Backend - SyncTask.java
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
 * 同步任务实体。
 *
 * <p>同步任务是一个“可运营对象”：它有负责人、状态、审批、优先级、调度配置、最近执行记录和触发方式。
 * 和模板相比，任务更接近前端任务列表、运维看板和执行控制台看到的对象。
 */
@Data
@TableName("data_sync_task")
public class SyncTask {

    /**
     * 任务主键。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 租户 ID。
     */
    private Long tenantId;

    /**
     * 项目 ID。
     *
     * <p>任务是运营对象，项目维度会参与“项目负责人能看哪些任务”、项目级配额、项目级同步成功率和成本统计。
     * 该字段通常继承自模板，允许任务创建时显式传入但必须与模板保持一致。
     */
    private Long projectId;

    /**
     * 工作空间 ID。
     *
     * <p>工作空间用于组织前端看板、运营台筛选和多团队协作。它不是租户边界的替代，而是租户内部的二级隔离。
     */
    private Long workspaceId;

    /**
     * 关联模板 ID。
     */
    private Long templateId;

    /**
     * 任务名称。
     */
    private String name;

    /**
     * 当前主状态。
     */
    private String currentState;

    /**
     * 审批状态。
     */
    private String approvalState;

    /**
     * 优先级，例如 HIGH、MEDIUM、LOW。
     */
    private String priority;

    /**
     * 调度配置 JSON。
     * 例如 cron、固定间隔、维护窗口、时区和错峰策略。
     */
    private String scheduleConfig;

    /**
     * 运行模式，例如 TEMPLATE、MANUAL、BACKFILL、REPLAY。
     */
    private String runMode;

    /**
     * 最近触发方式。
     */
    private String triggerType;

    /**
     * 负责人 ID。
     */
    private Long ownerId;

    /**
     * 最近一次执行记录 ID。
     *
     * <p>任务主表只保存最近一次 executionId，完整历史仍在 data_sync_execution。
     * 这样列表页可以快速展示最近运行结果，同时不会牺牲执行历史、失败样本和 checkpoint 追溯能力。
     */
    private Long lastExecutionId;

    /**
     * 是否需要人工介入。
     *
     * <p>该字段是面向运营和商用交付的“显式告警标记”。
     * 仅靠 currentState=AWAITING_OPERATOR_ACTION 也可以表达状态，但列表筛选、告警规则、工单推送通常更适合使用布尔字段。
     */
    private Boolean attentionRequired;

    /**
     * 人工介入原因。
     *
     * <p>这里保存的是给用户或运营人员看的短摘要，例如“超过最大退避次数，可能是目标端限流或执行器反复失联”。
     * 详细错误仍应落到 data_sync_error_sample、审计记录或未来的告警事件表中，避免主表膨胀。
     */
    private String attentionReason;

    /**
     * 任务说明。
     */
    private String description;

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
