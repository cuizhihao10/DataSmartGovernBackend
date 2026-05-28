/**
 * @Author : Cui
 * @Date: 2026/05/23 21:36
 * @Description DataSmart Govern Backend - AgentSkillRegistryProperties.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent Skill 注册表配置。
 *
 * <p>Skill 是 DataSmart Agent 平台的“能力包”抽象，不只是 prompt 文本。
 * 一个 Skill 应该把工具依赖、权限要求、记忆依赖、审批策略、风险等级和示例工作流放在一起，
 * 让 Python Runtime、智能网关、Java 控制面和后续插件市场都能用统一契约理解它。
 *
 * <p>本类故意独立于 `AgentRuntimeProperties`，而不是继续向那个类里追加内部类。
 * 原因有两个：
 * 1. 工具目录、模型路由、会话控制和 Skill 治理属于不同职责；
 * 2. 当前项目要求控制单文件规模，独立配置类能避免 `AgentRuntimeProperties` 演变成上帝配置类。
 */
@Data
@ConfigurationProperties(prefix = "datasmart.agent-runtime")
public class AgentSkillRegistryProperties {

    /**
     * Skill 注册表。
     *
     * <p>Key 建议使用稳定 skillCode，例如 `quality.rule.design`。
     * Value 描述该 Skill 的治理元数据。后续如果迁移到数据库或独立 Skill 市场，仍应保持这些字段的语义。
     */
    private Map<String, SkillDefinitionProperties> skillRegistry = new LinkedHashMap<>();

    /**
     * 单个 Skill 定义。
     *
     * <p>配置项先保持轻量，避免过早引入复杂 DSL。
     * 当后续需要可视化编排、多步骤工作流、prompt 模板版本和测试样例时，可以在该结构基础上演进。
     */
    @Data
    public static class SkillDefinitionProperties {

        /**
         * 是否启用该 Skill。
         *
         * <p>禁用 Skill 不会出现在默认 descriptor 列表中，适合灰度发布、租户裁剪或故障临时下线。
         */
        private Boolean enabled = true;

        /**
         * 稳定 Skill 编码。
         */
        private String skillCode;

        /**
         * 展示名称。
         */
        private String displayName;

        /**
         * Skill 说明。
         */
        private String description;

        /**
         * 归属治理域，例如 DATASOURCE、DATA_QUALITY、TASK_MANAGEMENT、PERMISSION_ADMIN。
         */
        private String domain = "GENERAL_GOVERNANCE";

        /**
         * 该 Skill 通常依赖的工具编码。
         *
         * <p>这里不直接嵌入工具定义，而是引用 toolCode，让工具治理继续由工具注册表负责。
         */
        private List<String> requiredTools = new ArrayList<>();

        /**
         * 该 Skill 所需的平台权限。
         */
        private List<String> requiredPermissions = new ArrayList<>();

        /**
         * 该 Skill 依赖的记忆类型。
         *
         * <p>示例：SEMANTIC、EPISODIC、PROCEDURAL。
         * Python Runtime 可据此决定是否需要先检索元数据、历史异常或操作流程。
         */
        private List<String> memoryDependencies = new ArrayList<>();

        /**
         * 风险等级。
         */
        private String riskLevel = "LOW";

        /**
         * 审批策略。
         *
         * <p>示例：NONE、DRAFT_REVIEW、AUDIT_ONLY、HUMAN_APPROVAL_REQUIRED。
         */
        private String approvalPolicy = "NONE";

        /**
         * 是否必须限定租户范围。
         */
        private Boolean tenantScoped = true;

        /**
         * 是否必须限定项目范围。
         */
        private Boolean projectScoped = true;

        /**
         * Skill 选择、执行和写入记忆时是否必须审计。
         */
        private Boolean auditRequired = true;

        /**
         * 默认记忆范围。
         */
        private String defaultMemoryScope = "PROJECT";

        /**
         * Skill 产物默认保留天数。
         */
        private Integer retentionDays = 30;

        /**
         * 规则式 Skill 选择的触发关键词。
         */
        private List<String> triggerKeywords = new ArrayList<>();

        /**
         * 示例目标，用于前端说明、评测样例和后续 Skill 市场展示。
         */
        private List<String> examples = new ArrayList<>();
    }
}
