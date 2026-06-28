/**
 * @Author : Cui
 * @Date: 2026/06/28 22:45
 * @Description DataSmart Govern Backend - AgentToolActionSubmissionFactQueryResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * Agent 工具真实提交事实查询响应。
 *
 * <p>该响应回答一个非常收敛的问题：给定 `commandId` 后，当前调用方是否能看到对应的真实提交事实，
 * 以及该事实是否还处于需要人工对账的状态。接口不会提供全量扫描能力，也不会根据模糊条件批量导出事实，
 * 这是为了避免运维查询接口变成跨租户数据发现面。</p>
 *
 * @param queryMode 查询模式说明，固定表达“必须按 commandId 查询”。
 * @param payloadPolicy 响应低敏策略，明确禁止返回正文、prompt、SQL、样本和内部端点。
 * @param commandId 本次查询定位的 commandId。
 * @param tenantId 经过 Header 数据范围收口后的租户条件。
 * @param projectId 经过 Header 数据范围收口后的项目条件。
 * @param actorId 经过 Header 数据范围收口后的 actor 条件。
 * @param runId 查询方主动传入并经过规范化的 runId 条件。
 * @param sessionId 查询方主动传入并经过规范化的 sessionId 条件。
 * @param authorizedProjectIds PROJECT 范围下可见项目集合；空集合表示不可见。
 * @param factPresent true 表示命中并且当前数据范围允许查看该事实。
 * @param fact 命中的低敏事实视图；不可见或不存在时为 null。
 * @param evidenceCodes 支撑当前结论的机器证据码。
 * @param issueCodes 当前仍需关注的问题码。
 * @param recommendedActions 面向运维或后续自动 worker 的低敏下一步建议。
 */
public record AgentToolActionSubmissionFactQueryResponse(
        String queryMode,
        String payloadPolicy,
        String commandId,
        String tenantId,
        String projectId,
        String actorId,
        String runId,
        String sessionId,
        List<String> authorizedProjectIds,
        Boolean factPresent,
        AgentToolActionSubmissionFactView fact,
        List<String> evidenceCodes,
        List<String> issueCodes,
        List<String> recommendedActions
) {
}
