/**
 * @Author : Cui
 * @Date: 2026/06/04 00:08
 * @Description DataSmart Govern Backend - AgentToolRuntimeProtectionPolicyView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.time.Instant;
import java.util.List;

/**
 * Agent 工具运行时保护策略视图。
 *
 * <p>该 DTO 用于 Controller 对外返回，字段与内部 verdict 基本保持一致。
 * 之所以不直接暴露内部对象，是为了让接口契约和领域实现保持一层薄隔离：
 * 后续内部可以从 JVM 计数迁移到 Redis、数据库、服务网格或 quota-center，而接口字段保持稳定。</p>
 *
 * <p>字段设计原则：
 * - 返回容量和熔断指标，帮助前端/运维解释“为什么现在不能执行”；
 * - 返回 issueCodes 作为低基数机器可读原因，方便未来接 Prometheus/Grafana；
 * - 返回中文 reasons/actions 作为学习、排障和用户提示；
 * - 不返回工具参数、模型正文、下游响应体或任何敏感业务数据。</p>
 */
public record AgentToolRuntimeProtectionPolicyView(
        String sessionId,
        String runId,
        String auditId,
        String toolCode,
        String targetService,
        Long tenantId,
        Boolean protectionEnabled,
        Boolean allowed,
        Integer globalInFlight,
        Integer tenantInFlight,
        Integer targetServiceInFlight,
        Integer maxGlobalInFlight,
        Integer maxTenantInFlight,
        Integer maxTargetServiceInFlight,
        Boolean circuitBreakerEnabled,
        Boolean circuitOpen,
        Instant circuitOpenUntil,
        Integer consecutiveFailures,
        List<String> issueCodes,
        List<String> reasons,
        List<String> recommendedActions
) {
}
