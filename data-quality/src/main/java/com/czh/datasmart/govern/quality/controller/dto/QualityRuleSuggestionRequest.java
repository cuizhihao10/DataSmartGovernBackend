/**
 * @Author : Cui
 * @Date: 2026/05/24 22:05
 * @Description DataSmart Govern Backend - QualityRuleSuggestionRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.controller.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

/**
 * 质量规则草案建议请求。
 *
 * <p>这个 DTO 面向 Agent 工具调用和未来前端“智能生成规则”按钮，不等同于 `CreateQualityRuleRequest`。
 * `CreateQualityRuleRequest` 会落库创建 DRAFT 规则，而本请求只让 data-quality 根据元数据和治理目标生成
 * “可审查的规则草案”。用户确认前，系统不会写入规则表，也不会启用扫描任务。</p>
 *
 * <p>为什么要先做草案接口，而不是让 Agent 直接创建规则：</p>
 * <p>1. 质量规则会影响后续生产检测、告警和报表，不能由模型直接写入启用；</p>
 * <p>2. 草案可以先进入前端确认、审批或批量编辑流程，符合商业化“人类在环”要求；</p>
 * <p>3. 该接口可以作为模型不可用时的确定性兜底规则生成器。</p>
 */
@Data
public class QualityRuleSuggestionRequest {

    /**
     * 租户 ID。
     * Agent Runtime 会从会话上下文传入，data-quality 侧仍保留请求体字段用于响应草案预填。
     */
    @NotNull(message = "tenantId 不能为空")
    private Long tenantId;

    /**
     * 项目 ID。
     * 该字段会参与 gateway 透传 PROJECT 数据范围校验，防止 Agent 为未授权项目生成治理建议。
     */
    @NotNull(message = "projectId 不能为空")
    private Long projectId;

    /**
     * 工作空间 ID。
     * 允许为空，表示草案只归属到项目级。
     */
    private Long workspaceId;

    /**
     * 数据源 ID。
     * 草案建议通常基于某个 datasource-management 中登记的数据源。
     */
    @NotNull(message = "datasourceId 不能为空")
    private Long datasourceId;

    /**
     * 可选表名过滤。
     * 如果传入，建议生成器只围绕该表生成草案；如果为空，则从元数据中的前几张表选取候选。
     */
    private String tableName;

    /**
     * 用户或 Agent 提炼出的治理目标。
     * 例如“检查订单主键唯一性和金额异常值”“为客户表补基础完整性规则”。
     */
    @NotBlank(message = "businessGoal 不能为空")
    private String businessGoal;

    /**
     * 数据源元数据快照。
     * 这里通常来自 `datasource.metadata.read` 工具输出中的 `metadata`。
     * data-quality 不直接依赖 datasource-management DTO，而是消费 JSON Map，保持微服务契约解耦。
     */
    private Map<String, Object> metadata;

    /**
     * 最多返回多少条草案。
     * 该限制保护前端确认页、Agent 上下文和响应体大小，避免大表一次生成过多规则。
     */
    @Min(value = 1, message = "maxSuggestions 不能小于 1")
    @Max(value = 20, message = "maxSuggestions 不能大于 20")
    private Integer maxSuggestions;
}
