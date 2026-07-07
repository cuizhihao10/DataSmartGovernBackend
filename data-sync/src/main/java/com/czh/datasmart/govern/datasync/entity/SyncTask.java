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
     * 是否启用任务级自动调度。
     *
     * <p>该字段不是简单等同于 scheduleConfig 是否为空。真实生产系统里可能存在“先保存调度配置，但暂不启用”的场景，
     * 例如等待审批、等待维护窗口、等待源端授权或等待容量评估。只有 scheduleEnabled=true 且 currentState=SCHEDULED
     * 的任务，才会被后台 task scheduler 扫描并自动生成 SCHEDULED execution。</p>
     */
    private Boolean scheduleEnabled;

    /**
     * 下一次计划触发时间。
     *
     * <p>调度器只扫描 nextFireTime 小于等于当前时间的任务。把下一次触发时间持久化到任务表，而不是每轮都从 cron
     * 或 interval 重新推导，有两个好处：一是查询可以走索引，二是错过触发、跳过补偿、catch-up 等策略有稳定状态。</p>
     */
    private LocalDateTime nextFireTime;

    /**
     * 上一次成功派发 execution 的计划触发时间。
     *
     * <p>注意它表达的是“计划窗口的 fire time”，不是 worker 真正开始执行的 startedAt。这样排查定时任务时可以区分：
     * 任务原本几点该触发、调度器几点生成 execution、worker 几点认领并真正写入数据。</p>
     */
    private LocalDateTime lastFireTime;

    /**
     * 调度错过次数。
     *
     * <p>当服务停机、任务仍在运行导致不能并发触发、或 misfirePolicy=SKIP 明确跳过过期窗口时，该计数会增长。
     * 它用于运营台和告警判断“这个定时任务是不是长期跟不上计划节奏”。</p>
     */
    private Integer scheduleMisfireCount;

    /**
     * 调度派发次数。
     *
     * <p>每成功创建一条 SCHEDULED execution，该计数增加。它和 data_sync_execution 历史记录互相印证：
     * task 表用于列表快速展示，execution 表用于完整历史追踪。</p>
     */
    private Long scheduleDispatchCount;

    /**
     * 调度版本号。
     *
     * <p>多实例 data-sync 同时扫描到同一个 due task 时，必须通过数据库条件更新完成抢占裁决。
     * scheduleVersion 就是这把“乐观锁”：调度器读取到版本 N 后，只有还能用 N 更新成功的实例可以创建 execution；
     * 其它实例更新 0 行后直接跳过，避免重复触发。</p>
     */
    private Long scheduleVersion;

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
