/**
 * @Author : Cui
 * @Date: 2026/06/28 22:45
 * @Description DataSmart Govern Backend - AgentToolActionSubmissionManualResolutionResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * Agent 工具真实提交事实人工对账响应。
 *
 * <p>该响应同时返回更新前和更新后的低敏视图，便于运维台展示“如果执行会改变什么”。
 * 当 `dryRun=true` 时，`after` 只是预览结果，不代表已经写入仓储；当 `dryRun=false` 且 `updated=true` 时，
 * `after` 才是持久化后的事实状态。</p>
 *
 * @param accepted true 表示请求通过合同校验和权限校验。
 * @param dryRun true 表示本次只预览，不修改提交事实。
 * @param updated true 表示已经把 UNKNOWN 事实更新为目标终态。
 * @param commandId 本次人工对账定位的 commandId。
 * @param before 更新前的低敏提交事实。
 * @param after 更新后或预览后的低敏提交事实。
 * @param evidenceCodes 支撑本次对账结果的机器证据码。
 * @param issueCodes 当前仍需关注的问题码。
 * @param recommendedActions 下一步建议。
 * @param message 面向调用方的低敏处理说明。
 */
public record AgentToolActionSubmissionManualResolutionResponse(
        Boolean accepted,
        Boolean dryRun,
        Boolean updated,
        String commandId,
        AgentToolActionSubmissionFactView before,
        AgentToolActionSubmissionFactView after,
        List<String> evidenceCodes,
        List<String> issueCodes,
        List<String> recommendedActions,
        String message
) {
}
