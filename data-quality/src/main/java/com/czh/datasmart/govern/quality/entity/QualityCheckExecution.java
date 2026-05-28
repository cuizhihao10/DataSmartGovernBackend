/**
 * @Author : Cui
 * @Date: 2026/04/27 21:25
 * @Description DataSmart Govern Backend - QualityCheckExecution.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 质量检测执行记录。
 *
 * <p>质量检测不应只有最终报告，还应有“执行动作本身”的记录。
 * 例如一次检测可能因为规则未启用失败，也可能成功生成报告但报告结果为 FAILED。
 *
 * <p>把执行记录和报告分开后，未来可以支撑：
 * 1. 查看某条规则跑过多少次、最近一次是否执行成功；
 * 2. 排查检测任务失败但没有报告的场景；
 * 3. 统计检测耗时、失败率和触发来源；
 * 4. 与 task-management 的任务执行记录或 data-quality 智能体执行链路打通。
 */
@Data
@TableName("quality_check_execution")
public class QualityCheckExecution {

    /**
     * 执行记录主键。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 租户 ID。
     *
     * <p>执行记录是质量检测链路的事实数据，需要继承规则的租户边界。
     * 后续统计执行失败率、执行耗时、队列积压和租户级资源消耗时，可以不必频繁 join 规则表。</p>
     */
    private Long tenantId;

    /**
     * 项目 ID。
     *
     * <p>该字段冗余自质量规则，用于项目级执行历史、项目 SLA、项目看板和项目负责人排障。
     * 冗余不是重复设计，而是为了让高频运营查询能直接命中执行表索引，减少跨表查询成本。</p>
     */
    private Long projectId;

    /**
     * 工作空间 ID。
     *
     * <p>该字段冗余自质量规则，用于空间级运行证据筛选。
     * 例如同一个项目下“测试空间”的规则执行失败，不应影响“生产空间”的质量大盘判断。</p>
     */
    private Long workspaceId;

    /**
     * 规则 ID。
     */
    private Long ruleId;

    /**
     * 同一规则下第几次执行。
     */
    private Long executionNo;

    /**
     * 触发类型。
     *
     * <p>当前人工接口默认 MANUAL；后续可扩展 SCHEDULED、TASK_TRIGGERED、AGENT_TRIGGERED、API_TRIGGERED。
     */
    private String triggerType;

    /**
     * 执行状态：RUNNING、SUCCESS、FAILED。
     */
    private String executionState;

    /**
     * 触发人或触发主体。
     *
     * <p>人工执行时通常是用户或 system；任务执行器回调时通常保存 executorId。
     * 这个字段偏“业务触发主体”，而下面的 executorId 更偏“实际运行实例”。
     */
    private String operator;

    /**
     * task-management 任务 ID。
     *
     * <p>当质量检测由任务中心调度时，该字段保存任务主表 ID。
     * 这样质量执行记录可以反查任务创建人、任务优先级、重试次数、运维干预日志等信息。
     * 人工同步执行的检测没有任务上下文时，该字段可以为空。
     */
    private Long taskId;

    /**
     * task-management 单次执行记录 ID。
     *
     * <p>同一个任务可能多次运行，例如首次失败后重试。
     * taskRunId 用来精确标识当前质量执行记录来自任务中心的哪一次 run，
     * 后续排查“第几次重试生成了哪份报告”时会非常有用。
     */
    private Long taskRunId;

    /**
     * 实际执行器实例 ID。
     *
     * <p>生产环境通常会有多个质量执行器实例并行消费任务。
     * 保存 executorId 可以支持按实例统计耗时、失败率、版本问题和机器资源热点。
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
     * 生成的报告 ID。
     */
    private Long reportId;

    /**
     * 扫描计划快照 JSON。
     *
     * <p>质量规则和扫描参数可能在任务提交后被修改。
     * 把本次执行实际使用的扫描计划快照保存下来，可以确保历史执行和报告具备可解释性：
     * 审计人员看到的是“当时跑的范围、模式、采样限制、超时配置”，而不是当前最新配置。
     */
    private String scanPlanSnapshot;

    /**
     * 失败原因或补充说明。
     */
    private String message;

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
