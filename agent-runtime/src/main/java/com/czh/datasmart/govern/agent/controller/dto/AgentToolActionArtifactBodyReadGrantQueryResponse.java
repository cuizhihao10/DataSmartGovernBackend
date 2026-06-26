/**
 * @Author : Cui
 * @Date: 2026/06/26 21:07
 * @Description DataSmart Govern Backend - AgentToolActionArtifactBodyReadGrantQueryResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * artifact 正文读取 grant fact 低敏查询响应。
 *
 * <p>响应显式返回 storeMode、queryMode、evidenceCodes 和 missingCapabilities，是为了让管理台知道
 * 当前查询到底来自 memory 还是 MySQL，以及这条链路仍缺哪些生产化能力。这样比只返回列表更适合长期收敛项目。</p>
 */
public record AgentToolActionArtifactBodyReadGrantQueryResponse(
        /** 单次查询实际采用的上限。 */
        Integer appliedLimit,

        /** 当前查询命中数量。 */
        Integer totalMatched,

        /** 当前仓储模式，例如 MEMORY 或 MYSQL。 */
        String storeMode,

        /** 查询模式说明，固定强调这是低敏 grant fact 查询。 */
        String queryMode,

        /** payload 策略说明。 */
        String payloadPolicy,

        /** 当前 store 中记录总量，用于低频诊断，不建议生产高频轮询。 */
        Integer currentStoreSize,

        /** 查询条件中的 grant 引用。 */
        String grantDecisionReference,

        /** 查询条件中的 commandId。 */
        String commandId,

        /** 查询条件中的 artifactReference。 */
        String artifactReference,

        /** 收口后的租户条件。 */
        String scopedTenantId,

        /** 收口后的项目条件。 */
        String scopedProjectId,

        /** 收口后的 actor 条件。 */
        String scopedActorId,

        /** 收口后的 run 条件。 */
        String scopedRunId,

        /** 收口后的 session 条件。 */
        String scopedSessionId,

        /** 工具编码过滤。 */
        String toolCode,

        /** 状态过滤。 */
        String status,

        /** PROJECT 数据范围下的授权项目集合；null 表示无集合限制，空集合表示无项目可见。 */
        List<String> authorizedProjectIds,

        /** 本次查询链路产生的低敏证据码。 */
        List<String> evidenceCodes,

        /** 仍未完成的生产化能力。 */
        List<String> missingCapabilities,

        /** 低敏 grant fact 列表。 */
        List<AgentToolActionArtifactBodyReadGrantFactView> grants
) {
}
