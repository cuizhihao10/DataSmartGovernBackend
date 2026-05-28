/**
 * @Author : Cui
 * @Date: 2026/05/24 22:18
 * @Description DataSmart Govern Backend - QualityRuleSuggestRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.tool;

import java.util.Map;

/**
 * Agent 调用 data-quality 生成质量规则草案建议时使用的本地请求契约。
 *
 * <p>它与 data-quality 模块内的 `QualityRuleSuggestionRequest` 字段对齐，但不直接依赖对方 DTO。
 * 这样做和 `DatasourceMetadataReadRequest` 一样，是为了保持微服务独立演进：agent-runtime 只知道 HTTP JSON
 * 合同，不把 data-quality 的 Java Controller DTO 编译进自己的模块边界。</p>
 */
public record QualityRuleSuggestRequest(
        /**
         * 当前 Agent 会话所属租户。
         */
        Long tenantId,

        /**
         * 当前 Agent 会话所属项目。
         */
        Long projectId,

        /**
         * 当前 Agent 会话所属工作空间，可为空。
         */
        Long workspaceId,

        /**
         * 本次规则草案所基于的数据源 ID。
         */
        Long datasourceId,

        /**
         * 可选表名过滤。
         */
        String tableName,

        /**
         * 用户治理目标或 Python AgentPlan 提炼出的业务目标。
         */
        String businessGoal,

        /**
         * 元数据快照，通常来自 datasource.metadata.read 的输出。
         */
        Map<String, Object> metadata,

        /**
         * 最多返回多少条草案。
         */
        Integer maxSuggestions) {
}
