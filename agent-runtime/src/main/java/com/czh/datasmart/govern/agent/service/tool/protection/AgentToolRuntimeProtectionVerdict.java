/**
 * @Author : Cui
 * @Date: 2026/06/03 23:59
 * @Description DataSmart Govern Backend - AgentToolRuntimeProtectionVerdict.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.tool.protection;

import java.time.Instant;
import java.util.List;

/**
 * Agent 工具运行时保护判定结果。
 *
 * <p>该对象面向“执行容量”和“下游健康”两个维度，而不是替代 sandbox verdict。
 * 典型调用链路是：
 * 1. sandbox verdict 先判断工具计划是否安全；
 * 2. runtime-protection verdict 再判断当前是否应该执行；
 * 3. 如果两个 verdict 都允许，执行框架才会调用具体工具适配器。</p>
 *
 * <p>返回值刻意只包含低敏运行指标，例如 in-flight 数量、目标服务、issueCodes 和推荐动作。
 * 它不会暴露工具参数、模型输出、用户数据或下游响应正文，避免诊断接口变成敏感数据泄露面。</p>
 *
 * @param auditId 工具执行审计 ID，用于把 verdict 和具体工具计划对应起来。
 * @param toolCode 工具编码，用于前端和运维台展示。
 * @param targetService 目标业务微服务，例如 datasource-management。
 * @param tenantId 租户 ID，用于解释租户级并发保护。
 * @param protectionEnabled 是否启用运行时保护。
 * @param allowed 当前是否允许进入真实工具执行。
 * @param globalInFlight 当前 JVM 内所有工具执行中的数量。
 * @param tenantInFlight 当前租户在本 JVM 内执行中的数量。
 * @param targetServiceInFlight 当前目标服务在本 JVM 内执行中的数量。
 * @param maxGlobalInFlight 全局本地并发上限。
 * @param maxTenantInFlight 租户本地并发上限。
 * @param maxTargetServiceInFlight 目标服务本地并发上限。
 * @param circuitBreakerEnabled 是否启用连续失败熔断。
 * @param circuitOpen 当前目标服务熔断是否打开。
 * @param circuitOpenUntil 熔断打开截止时间；为 null 表示未打开。
 * @param consecutiveFailures 当前目标服务连续失败次数。
 * @param issueCodes 低基数问题码，后续可直接接入指标和告警。
 * @param reasons 面向用户/运维的中文解释。
 * @param recommendedActions 推荐处理动作。
 */
public record AgentToolRuntimeProtectionVerdict(
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
