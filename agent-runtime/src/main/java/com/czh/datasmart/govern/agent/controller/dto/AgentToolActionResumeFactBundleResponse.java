/**
 * @Author : Cui
 * @Date: 2026/06/16 00:00
 * @Description DataSmart Govern Backend - AgentToolActionResumeFactBundleResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 工具动作恢复事实包响应。
 *
 * <p>该响应是给 Python AI Runtime / Agent Host 消费的“控制面事实摘要”。
 * 它不会告诉调用方事实值是什么，只告诉调用方哪些事实类型已经由 Java 控制面采信、哪些缺失、哪些被否决。
 * 这样 Python 在 resume-preview 中可以继续保持 preview-only，并把真实副作用留给 Java outbox/worker 链路。</p>
 *
 * @param schemaVersion 响应契约版本。
 * @param previewOnly true 表示本接口只查询事实，不执行工具、不派发 worker、不修改 checkpoint。
 * @param queryBoundary 查询边界说明。
 * @param payloadPolicy 敏感信息策略说明。
 * @param requestedLocator 低敏请求定位摘要；不包含 approvalFactId、payloadReference、targetEndpoint。
 * @param requiredFactTypes 本次被检查的事实类型。
 * @param availableFactTypes 已可采信的事实类型。
 * @param missingFactTypes 缺失或未评估的事实类型。
 * @param rejectedFactTypes 被服务端明确否决的事实类型。
 * @param facts 单项事实状态。
 * @param outboxSummary 可选 command outbox 低敏摘要。
 * @param receiptSummary 可选 worker/dry-run receipt 低敏摘要。
 * @param recommendedActions 下一步建议，供 Python Runtime、运维台或前端解释使用。
 * @param productionReadiness 当前能力距离生产恢复执行仍缺的治理项。
 * @param generatedAt 响应生成时间。
 */
public record AgentToolActionResumeFactBundleResponse(
        String schemaVersion,
        Boolean previewOnly,
        String queryBoundary,
        String payloadPolicy,
        Map<String, Object> requestedLocator,
        List<String> requiredFactTypes,
        List<String> availableFactTypes,
        List<String> missingFactTypes,
        List<String> rejectedFactTypes,
        List<AgentToolActionResumeFactView> facts,
        AgentToolActionResumeFactOutboxSummaryView outboxSummary,
        AgentToolActionResumeFactReceiptSummaryView receiptSummary,
        List<String> recommendedActions,
        Map<String, Object> productionReadiness,
        Instant generatedAt
) {

    public AgentToolActionResumeFactBundleResponse {
        /*
         * requestedLocator 中有些字段允许为 null，例如调用方没有传 sessionId/runId。
         * JDK 的 Map.copyOf 不允许 null value，因此这里使用 LinkedHashMap + unmodifiableMap，
         * 既保留响应字段顺序，又不因为“未提供某个定位符”导致序列化前 NPE。
         */
        requestedLocator = requestedLocator == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(requestedLocator));
        requiredFactTypes = requiredFactTypes == null ? List.of() : List.copyOf(requiredFactTypes);
        availableFactTypes = availableFactTypes == null ? List.of() : List.copyOf(availableFactTypes);
        missingFactTypes = missingFactTypes == null ? List.of() : List.copyOf(missingFactTypes);
        rejectedFactTypes = rejectedFactTypes == null ? List.of() : List.copyOf(rejectedFactTypes);
        facts = facts == null ? List.of() : List.copyOf(facts);
        recommendedActions = recommendedActions == null ? List.of() : List.copyOf(recommendedActions);
        productionReadiness = productionReadiness == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(productionReadiness));
    }
}
