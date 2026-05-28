/**
 * @Author : Cui
 * @Date: 2026/05/27 00:00
 * @Description DataSmart Govern Backend - AgentRuntimeEventProjectionQuery.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import java.util.List;

/**
 * Agent runtime event 投影查询条件。
 *
 * <p>该查询对象服务“Agent 运行事件中心”的第一版。它故意覆盖 run/session/request/tenant/project/actor
 * 多个维度，而不是只支持 runId，是因为真实产品中的排障入口很多：
 * - 前端详情页通常按 runId 查询；
 * - WebSocket 断线重连问题通常按 sessionId 查询；
 * - HTTP 网关日志通常只知道 requestId；
 * - 项目负责人和审计员通常按 tenantId/projectId/actorId 过滤。</p>
 */
public record AgentRuntimeEventProjectionQuery(
        String tenantId,
        String projectId,
        String actorId,
        String requestId,
        String runId,
        String sessionId,
        String eventType,
        String severity,
        Integer limit,
        List<String> authorizedProjectIds
) {

    /**
     * 兼容早期调用方的构造方法。
     *
     * <p>3.87 阶段查询对象只包含“用户显式传入的查询条件”；3.89 开始，服务层会把 gateway 透传的
     * PROJECT 数据范围继续翻译成 `authorizedProjectIds`。保留这个构造方法，是为了让已有单元测试、
     * 内部调试代码和未来无需权限收口的系统任务不必一次性全部改动。</p>
     */
    public AgentRuntimeEventProjectionQuery(String tenantId,
                                            String projectId,
                                            String actorId,
                                            String requestId,
                                            String runId,
                                            String sessionId,
                                            String eventType,
                                            String severity,
                                            Integer limit) {
        this(tenantId, projectId, actorId, requestId, runId, sessionId, eventType, severity, limit, null);
    }

    /**
     * 规范化单次查询上限。
     *
     * <p>即使当前是内存仓储，也要提前形成分页/限量意识。生产上如果直接允许无限查询，
     * 很容易在某个 run 事件量很大或 Kafka 回放后把控制面接口拖垮。</p>
     */
    public int normalizedLimit() {
        if (limit == null || limit <= 0) {
            return 100;
        }
        return Math.min(limit, 1000);
    }

    /**
     * 规范化授权项目集合。
     *
     * <p>返回值有三个语义，后续持久化查询实现必须保持一致：</p>
     * <p>1. `null`：没有额外的项目集合约束，例如 TENANT/PLATFORM 范围；</p>
     * <p>2. 空集合：明确是 PROJECT 范围但没有任何授权项目，应返回空结果，不能退化成全量可见；</p>
     * <p>3. 非空集合：只允许命中集合内的 projectId。</p>
     */
    public List<String> normalizedAuthorizedProjectIds() {
        if (authorizedProjectIds == null) {
            return null;
        }
        return authorizedProjectIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }
}
