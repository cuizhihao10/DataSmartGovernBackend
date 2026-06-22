/**
 * @Author : Cui
 * @Date: 2026/06/22 20:19
 * @Description DataSmart Govern Backend - QualityExecutionDiagnosticsExecutionView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.controller.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 质量执行诊断中的最近执行快照。
 *
 * <p>这个 DTO 专门服务运维诊断页的“最近几次发生了什么”区域，而不是完整执行详情页。
 * 因此它只暴露低敏元数据，例如 executionId、ruleId、执行状态、开始/结束时间和是否存在说明，
 * 不暴露 scanPlanSnapshot、message 正文、SQL、样本载荷、连接信息或异常明细值。</p>
 *
 * <p>为什么不能直接返回 {@code QualityCheckExecution} 实体：
 * 执行实体里包含 scanPlanSnapshot 和 message。scanPlanSnapshot 可能包含库名、表名、字段名、
 * 采样策略等上下文；message 在失败场景下也可能包含下游异常摘要。诊断接口面向运营看板，
 * 默认应做到“能判断问题方向，但不能拿到敏感正文”。</p>
 */
@Data
public class QualityExecutionDiagnosticsExecutionView {

    /**
     * data-quality 内部执行记录 ID。
     */
    private Long executionId;

    /**
     * 租户 ID，用于多租户运营排障时确认问题归属。
     */
    private Long tenantId;

    /**
     * 项目 ID，用于项目级质量运营和权限过滤。
     */
    private Long projectId;

    /**
     * 工作空间 ID，用于区分研发、测试、生产等空间内的质量运行证据。
     */
    private Long workspaceId;

    /**
     * 质量规则 ID。
     */
    private Long ruleId;

    /**
     * 同一规则下的第几次执行。
     */
    private Long executionNo;

    /**
     * 触发类型，例如 MANUAL、SCHEDULED、TASK_TRIGGERED。
     */
    private String triggerType;

    /**
     * 执行状态：RUNNING、SUCCESS、FAILED。
     */
    private String executionState;

    /**
     * 触发主体或执行主体摘要。
     *
     * <p>这里保留 operator 是为了快速判断执行来自人工、系统还是执行器。
     * 如果未来 operator 可能携带账号、邮箱或外部身份，应继续升级为 hash 或 displayName。</p>
     */
    private String operator;

    /**
     * task-management 任务 ID。
     */
    private Long taskId;

    /**
     * task-management 单次执行 run ID。
     */
    private Long taskRunId;

    /**
     * 实际运行本次扫描的执行器实例 ID。
     */
    private String executorId;

    /**
     * 开始时间。
     */
    private LocalDateTime startedAt;

    /**
     * 结束时间。
     */
    private LocalDateTime finishedAt;

    /**
     * 执行耗时，单位毫秒。
     */
    private Long durationMs;

    /**
     * 生成的质量报告 ID。
     */
    private Long reportId;

    /**
     * 是否存在 scanPlanSnapshot。
     *
     * <p>只返回存在性，不返回正文。这样运营人员知道“本次执行是否有计划快照可供授权详情页查看”，
     * 但诊断接口不会直接泄露数据源、表字段、采样配置或策略细节。</p>
     */
    private Boolean scanPlanSnapshotAvailable;

    /**
     * 是否存在执行说明或失败摘要。
     *
     * <p>只返回存在性，不返回正文，避免把 SQL、连接串、内部 endpoint、下游异常正文或样本信息带到诊断大盘。</p>
     */
    private Boolean messageAvailable;

    /**
     * 本视图对敏感正文的可见性策略说明。
     */
    private String detailVisibilityPolicy;
}
