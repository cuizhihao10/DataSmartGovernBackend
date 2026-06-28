/**
 * @Author : Cui
 * @Date: 2026/06/28 13:26
 * @Description DataSmart Govern Backend - QualityRemediationTaskDraftRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.tool;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * `quality.remediation.task.draft` 工具调用 data-quality 时使用的受控请求模型。
 *
 * <p>这个 record 不直接复用 data-quality 模块里的 DTO，原因是 agent-runtime 与 data-quality 是独立
 * 微服务，控制面契约应通过 HTTP JSON 稳定，而不是通过 Java 类依赖强耦合。字段命名保持与
 * `QualityRemediationTaskRequest` 一致，方便 Jackson 序列化后被 data-quality 反序列化。</p>
 *
 * <p>安全边界：该请求只承载低敏筛选条件和 dry-run 控制字段。它不包含用户原始 prompt、objective、
 * SQL、异常样本、observedValue、模型输出、完整工具 arguments、凭据或内部 endpoint。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QualityRemediationTaskDraftRequest(
        /** 租户 ID，优先来自 Agent Session，而不是模型参数。 */
        Long tenantId,

        /** 项目 ID，优先来自 Agent Session，用于约束下游 PROJECT 数据范围。 */
        Long projectId,

        /** 工作空间 ID，优先来自 Agent Session；为空时表示项目级治理任务草案。 */
        Long workspaceId,

        /** 质量报告 ID，用于从一份已生成报告创建治理任务草案。 */
        Long reportId,

        /** 质量规则 ID，用于从异常工作台按规则筛选创建治理任务草案。 */
        Long ruleId,

        /** 异常类型筛选条件，例如 NULL_VALUE、DUPLICATE_VALUE、FORMAT_INVALID。 */
        String anomalyType,

        /** 异常字段名，只作为低敏元数据定位，不携带字段值或样本。 */
        String fieldName,

        /** 异常严重级别，例如 CRITICAL、HIGH、MEDIUM、LOW。 */
        String severity,

        /** 检测目标低敏路径，例如表名、Topic 或文件对象标识。 */
        String targetObject,

        /** 异常创建时间起点，使用 ISO 字符串交给 data-quality 解析。 */
        String startTime,

        /** 异常创建时间终点，使用 ISO 字符串交给 data-quality 解析。 */
        String endTime,

        /** 治理类型，例如 MANUAL_REVIEW、CLEANING_PLAN、SOURCE_SYSTEM_FIX。 */
        String remediationType,

        /** 低敏治理原因，用于任务说明和审批页展示。 */
        String reason,

        /** 低敏治理建议编码或简述，不是可执行清洗脚本。 */
        String recommendation,

        /** 建议负责人；为空时由 data-quality 使用当前 actor 或默认服务账号。 */
        Long assigneeActorId,

        /** 任务优先级，非法值会在工厂中回退到 MEDIUM。 */
        String priority,

        /** 后续真实任务可能使用的最大重试次数；dry-run 阶段只是预览字段。 */
        Integer maxRetryCount,

        /** TOP 聚合数量，避免把异常全量明细塞进任务草案。 */
        Integer aggregationLimit,

        /** 强制 true：Agent 当前只做治理任务草案预演，不提交真实 task-management 任务。 */
        Boolean dryRun
) {
}
