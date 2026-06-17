/**
 * @Author : Cui
 * @Date: 2026/06/17 00:00
 * @Description DataSmart Govern Backend - AgentToolActionWorkerReceiptIndexQuery.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import java.util.List;

/**
 * worker receipt 专用索引查询条件。
 *
 * <p>该对象把恢复事实包服务中的权限收口结果传递给索引仓储。
 * 注意它不是 Controller DTO，不直接接收外部输入；它应由服务层基于
 * {@link AgentRuntimeEventProjectionQuery} 构造，从而复用 tenant/project/actor/run/session 和
 * authorizedProjectIds 的既有数据范围语义。</p>
 */
public record AgentToolActionWorkerReceiptIndexQuery(
        /** 必填，按 commandId 查询 receipt。缺失时仓储应返回空结果。 */
        String commandId,

        /** 可选工具编码。历史事件可能没有 toolCode，因此匹配时只在两边都有值时才强校验。 */
        String toolCode,

        /** 租户过滤条件，来自 gateway/permission-admin 数据范围收口后的查询对象。 */
        String tenantId,

        /** 项目过滤条件，来自 gateway/permission-admin 数据范围收口后的查询对象。 */
        String projectId,

        /** actor 过滤条件，来自访问上下文或请求自带的更窄范围。 */
        String actorId,

        /** run 过滤条件，避免同一个 commandId 在不同运行中被误采信。 */
        String runId,

        /** session 过滤条件，避免跨会话恢复事实污染。 */
        String sessionId,

        /** PROJECT 数据范围下的授权项目集合；null 表示无项目集合限制，空集合表示看不到任何项目。 */
        List<String> authorizedProjectIds,

        /** 单次查询上限，防止异常 command 重复 receipt 过多拖慢恢复预检。 */
        Integer limit
) {

    public AgentToolActionWorkerReceiptIndexQuery {
        commandId = text(commandId);
        toolCode = text(toolCode);
        tenantId = text(tenantId);
        projectId = text(projectId);
        actorId = text(actorId);
        runId = text(runId);
        sessionId = text(sessionId);
        authorizedProjectIds = authorizedProjectIds == null
                ? null
                : authorizedProjectIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    /**
     * 规范化查询上限。
     *
     * <p>专用索引当前是内存实现，但仍提前遵守分页/限量思维。
     * 后续 MySQL 实现应把该 limit 下沉到 SQL limit，不能取回全量后再在 JVM 中截断。</p>
     */
    public int normalizedLimit() {
        if (limit == null || limit <= 0) {
            return 50;
        }
        return Math.min(limit, 500);
    }

    private static String text(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }
}
