/**
 * @Author : Cui
 * @Date: 2026/06/28 15:10
 * @Description DataSmart Govern Backend - QualityRemediationTaskRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.controller.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 质量异常治理任务创建请求。
 *
 * <p>这个 DTO 表达的是“把已经发现的质量异常转成 task-management 中的治理/复核任务”。
 * 它不是清洗执行请求，也不会携带原始样本、字段观测值、SQL、prompt、模型输出或工具参数。
 * 真实商业产品里，质量异常闭环通常会分为三步：</p>
 *
 * <p>1. data-quality 发现异常并形成报告、异常聚合和低敏定位条件；</p>
 * <p>2. 运营人员、项目负责人或 Agent 基于这些低敏条件创建治理任务；</p>
 * <p>3. 后续由人工复核、清洗执行器、源系统整改或数据同步补偿链路处理任务。</p>
 *
 * <p>本请求只落地第 2 步的契约，避免在项目收敛阶段直接引入高风险的自动清洗写入。</p>
 */
@Data
public class QualityRemediationTaskRequest {

    /**
     * 租户 ID。
     *
     * <p>生产环境应优先使用 gateway 写入的 `X-DataSmart-Tenant-Id`，
     * 请求体中的 tenantId 只作为服务账号、离线脚本或本地联调的补充来源。
     * service 层会优先采用受控 Header，避免普通客户端通过请求体伪造跨租户治理任务。</p>
     */
    private Long tenantId;

    /**
     * 项目 ID。
     *
     * <p>治理任务必须尽量绑定到明确项目，原因是任务中心后续会按项目做待办归属、SLA、
     * 数据范围过滤和运营看板。如果没有 reportId，也没有明确 projectId，service 会拒绝创建，
     * 防止把一个跨项目异常筛选条件变成含义模糊的治理任务。</p>
     */
    private Long projectId;

    /**
     * 工作空间 ID。
     *
     * <p>用于把治理任务进一步收口到研发、测试、生产等空间。该字段不是必填，
     * 但如果异常来源已经具备 workspaceId，建议传入，便于后续做空间级质量趋势和责任划分。</p>
     */
    private Long workspaceId;

    /**
     * 质量报告 ID。
     *
     * <p>当用户从某份质量报告详情页点击“创建治理任务”时，应传入 reportId。
     * service 会从报告快照中派生 tenant/project/workspace/rule/targetObject 等低敏上下文，
     * 并继续按当前 actor 的 PROJECT 数据范围校验报告是否可访问。</p>
     */
    private Long reportId;

    /**
     * 质量规则 ID。
     *
     * <p>当用户不从具体报告入口创建，而是从异常工作台按规则筛选创建任务时使用。
     * 它只用于筛选异常事实和写入低敏任务 payload，不代表调用方拥有修改规则的权限。</p>
     */
    private Long ruleId;

    /**
     * 异常类型筛选条件，例如 NULL_VALUE、DUPLICATE_VALUE、FORMAT_INVALID。
     *
     * <p>该字段进入任务 payload 时只作为枚举式筛选摘要，不携带异常样本正文。</p>
     */
    private String anomalyType;

    /**
     * 异常字段筛选条件。
     *
     * <p>字段名本身通常属于低敏元数据，但仍会被长度限制和脱敏兜底处理。
     * 如果后续接入字段分级分类，敏感字段名可在这里进一步替换为字段指纹或别名。</p>
     */
    private String fieldName;

    /**
     * 异常严重级别筛选条件，例如 CRITICAL、HIGH、MEDIUM、LOW。
     */
    private String severity;

    /**
     * 检测目标筛选条件，例如库表、字段、Topic、文件对象或 API endpoint 的低敏路径。
     *
     * <p>这里允许模糊筛选，但不应传入连接串、内部 URL、SQL 或带凭据的完整地址。
     * service 会对明显敏感片段做兜底隐藏。</p>
     */
    private String targetObject;

    /**
     * 异常创建时间起点。
     *
     * <p>用于把治理任务限定在一个明确时间窗口内，避免把历史异常和当前待治理异常混在同一个任务中。</p>
     */
    private LocalDateTime startTime;

    /**
     * 异常创建时间终点。
     */
    private LocalDateTime endTime;

    /**
     * 治理类型。
     *
     * <p>当前建议值包括 MANUAL_REVIEW、CLEANING_PLAN、SOURCE_SYSTEM_FIX、RULE_TUNING。
     * service 不会因为该字段而直接执行清洗，它只用于给任务中心和后续 Agent 解释任务意图。</p>
     */
    private String remediationType;

    /**
     * 创建治理任务的原因。
     *
     * <p>原因会进入任务描述和低敏 payload，必须只写业务背景，不写 SQL、样本值、prompt、
     * 模型输出、工具参数、凭据或内部 endpoint。service 会做长度限制与敏感关键词兜底隐藏。</p>
     */
    private String reason;

    /**
     * 建议处理方式。
     *
     * <p>它可以是人工复核建议、源系统整改方向或后续清洗计划摘要。
     * 当前阶段只作为治理任务说明，不会被自动转换成可执行清洗脚本。</p>
     */
    private String recommendation;

    /**
     * 任务负责人。
     *
     * <p>不传时默认使用当前 actor；当前 actor 也不存在时回落到 data-quality 的服务账号配置。
     * 这样可以确保任务中心的“我的任务”“待办提醒”和 SLA 归属有明确负责人。</p>
     */
    private Long assigneeActorId;

    /**
     * 任务优先级。
     *
     * <p>允许 HIGH、MEDIUM、LOW。未传或非法时使用配置中的 defaultPriority，
     * 避免外部随意构造异常优先级污染任务队列。</p>
     */
    private String priority;

    /**
     * 最大重试次数。
     *
     * <p>治理任务通常是人工/流程类任务，不像扫描任务那样依赖源库瞬时可用性。
     * 这里仍保留该字段，是为了后续接入自动清洗执行器、通知回调或外部系统派单时复用任务中心重试能力。</p>
     */
    private Integer maxRetryCount;

    /**
     * TOP 聚合数量。
     *
     * <p>service 会把它限制在 1 到 50 之间。治理任务只需要少量 TOP 字段、TOP 类型和 TOP 严重级别，
     * 不应该把异常全量明细塞进 task params，否则会造成任务表膨胀和敏感数据扩散。</p>
     */
    private Integer aggregationLimit;

    /**
     * 下游任务创建幂等键。
     *
     * <p>该字段面向“真实提交到 task-management”的场景，尤其是 Agent Host、审批后 worker、补偿脚本或外部系统重试。
     * 当 data-quality 已经把质量治理请求转发给 task-management，但上游因为超时或进程重启无法确认结果时，
     * 后续重试必须使用同一个幂等键，task-management 才能复用第一次创建的治理任务。</p>
     *
     * <p>它不是治理原因、不是异常摘要，也不是 payload 指纹，只能保存低敏机器标识，例如
     * {@code tool-action:proposal:run:command}。不要把 SQL、prompt、样本数据、异常明细、工具参数正文、
     * 凭据或内部 URL 放进来；data-quality 只负责透传，最终校验和唯一约束由 task-management 承担。</p>
     */
    private String idempotencyKey;

    /**
     * 是否只预演。
     *
     * <p>true 表示只返回将要提交给 task-management 的低敏 payload 预览，不真正创建任务。
     * 这个开关适合前端确认页、Agent ToolPlan dry-run、人审前预览和联调测试。</p>
     */
    private Boolean dryRun;
}
