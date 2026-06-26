/**
 * @Author : Cui
 * @Date: 2026/06/26 21:05
 * @Description DataSmart Govern Backend - AgentToolActionArtifactBodyReadGrantQuery.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import java.util.List;

/**
 * artifact 正文读取 grant fact 的低敏查询条件。
 *
 * <p>该对象不是 Controller DTO，也不直接信任外部输入。Controller 接收到的 HTTP 参数会先交给
 * {@link AgentRuntimeEventProjectionAccessSupport} 做租户、项目和 actor 范围收口，然后服务层再构造本对象。
 * 这样 memory/MySQL 两种 Store 都只面对已经收口后的查询条件，避免不同实现各写一套权限规则。</p>
 *
 * <p>安全边界：查询条件只包含低敏定位符和机器状态，不包含 artifact 正文、sample、bucket/key、URL、token、
 * prompt、SQL、工具参数、模型输出、内部 endpoint 或人工备注正文。</p>
 */
public record AgentToolActionArtifactBodyReadGrantQuery(
        /**
         * 可选 grant 引用精确查询。
         *
         * <p>grantDecisionReference 不是 bearer token，但它可以精确定位一条授权事实；管理员撤销和单条排障
         * 通常应优先使用该字段。</p>
         */
        String grantDecisionReference,

        /**
         * 可选 commandId 查询。
         *
         * <p>为了避免管理接口被误用成全表浏览，本批次要求 grantDecisionReference 或 commandId 至少提供一个。
         * 后续真正审计报表可以另做按时间分页的审计查询服务。</p>
         */
        String commandId,

        /** 可选低敏 artifact 引用过滤，不包含 bucket/key 或真实路径。 */
        String artifactReference,

        /** 租户过滤条件，已经由访问上下文收口。 */
        String tenantId,

        /** 项目过滤条件，已经由访问上下文收口。 */
        String projectId,

        /** actor 过滤条件，SELF 范围下会被强制收口为当前 actor。 */
        String actorId,

        /** run 过滤条件，用于避免同一 commandId 在不同 run 之间误关联。 */
        String runId,

        /** session 过滤条件，用于会话级排障和 timeline 串联。 */
        String sessionId,

        /** 低敏工具编码过滤，不包含工具实参或目标地址。 */
        String toolCode,

        /** grant fact 状态过滤，例如 ACTIVE、EXPIRED、REVOKED。 */
        String status,

        /** PROJECT 数据范围下的授权项目集合；null 表示无项目集合限制，空集合表示无项目可见。 */
        List<String> authorizedProjectIds,

        /** 单次查询上限，Store 实现必须下沉到内存截断或 SQL LIMIT。 */
        Integer limit
) {

    public AgentToolActionArtifactBodyReadGrantQuery {
        grantDecisionReference = text(grantDecisionReference);
        commandId = text(commandId);
        artifactReference = text(artifactReference);
        tenantId = text(tenantId);
        projectId = text(projectId);
        actorId = text(actorId);
        runId = text(runId);
        sessionId = text(sessionId);
        toolCode = text(toolCode);
        status = text(status);
        authorizedProjectIds = authorizedProjectIds == null
                ? null
                : authorizedProjectIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    /**
     * 是否具备本批次允许的查询入口。
     *
     * <p>这里故意不允许只传 tenantId/status 做宽表查询。那类能力更像审计报表，应有独立分页、导出审批、
     * 频率限制和审计日志，不能混进执行控制面的轻量查询路由。</p>
     */
    public boolean hasRequiredSelector() {
        return grantDecisionReference != null || commandId != null;
    }

    /**
     * 规范化查询上限。
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
