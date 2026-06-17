/**
 * @Author : Cui
 * @Date: 2026/06/17 00:00
 * @Description DataSmart Govern Backend - AgentToolActionResumeFactBundleResponseSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionResumeFactBundleQueryRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionResumeFactView;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 恢复事实包响应辅助器。
 *
 * <p>把 requestedLocator、recommendedActions、productionReadiness 这些“响应解释层”逻辑从主 service 拆出，
 * 是为了保持 `AgentToolActionResumeFactBundleService` 专注于事实源聚合。这样后续继续扩展 locator index、
 * clarification store、worker receipt 持久化索引时，不会让主 service 再次超过 500 行。</p>
 */
final class AgentToolActionResumeFactBundleResponseSupport {

    private static final String FACT_APPROVAL = "APPROVAL_CONFIRMATION_FACT";
    private static final String FACT_CLARIFICATION = "CLARIFICATION_FACT";
    private static final String FACT_OUTBOX = "OUTBOX_WRITE_CONFIRMATION";
    private static final String FACT_WORKER_RECEIPT = "WORKER_RECEIPT_PROJECTION";

    private AgentToolActionResumeFactBundleResponseSupport() {
    }

    /**
     * 构造低敏请求定位摘要。
     *
     * <p>注意：approvalFactId、clarificationFactId 和 outboxId 只暴露“是否存在”，不暴露原始值。
     * commandId 当前已经是 Java outbox 的业务定位符，历史响应已经返回它；后续如需进一步降敏，可再改为 hash。</p>
     */
    static Map<String, Object> requestedLocator(AgentToolActionResumeFactBundleQueryRequest request,
                                                AgentRuntimeEventProjectionQuery scopedQuery,
                                                AgentToolActionResumeLocatorIndexService.EnrichmentResult enrichment) {
        Map<String, Object> locator = new LinkedHashMap<>();
        locator.put("checkpointIdPresent", hasText(request.checkpointId()));
        locator.put("threadIdPresent", hasText(request.threadId()));
        locator.put("sessionId", text(request.sessionId()));
        locator.put("runId", text(request.runId()));
        locator.put("commandId", text(request.commandId()));
        locator.put("outboxIdPresent", hasText(request.outboxId()));
        locator.put("approvalFactIdPresent", hasText(request.approvalFactId()));
        locator.put("clarificationFactIdPresent", hasText(request.clarificationFactId()));
        locator.put("toolCode", text(request.toolCode()));
        locator.put("scopedTenantId", scopedQuery.tenantId());
        locator.put("scopedProjectId", scopedQuery.projectId());
        locator.put("scopedActorId", scopedQuery.actorId());
        locator.put("locatorIndexHit", enrichment != null && enrichment.locatorIndexHit());
        locator.put("locatorIndexEvidenceCodes", enrichment == null ? List.of() : enrichment.evidenceCodes());
        return locator;
    }

    /**
     * 根据单项事实状态生成下一步建议。
     *
     * <p>这些建议是给 Python Runtime、运维台或未来前端解释用的低敏机器动作，不包含审批意见、工具参数或 payload。</p>
     */
    static List<String> recommendedActions(List<AgentToolActionResumeFactView> facts) {
        Set<String> actions = new LinkedHashSet<>();
        for (AgentToolActionResumeFactView fact : facts) {
            if (Boolean.TRUE.equals(fact.rejected())) {
                actions.add("RECREATE_OR_REPAIR_REJECTED_" + fact.factType());
                continue;
            }
            if (Boolean.TRUE.equals(fact.available())) {
                continue;
            }
            switch (fact.factType()) {
                case FACT_APPROVAL -> actions.add("WAIT_FOR_OR_RECREATE_PERMISSION_ADMIN_APPROVAL_FACT");
                case FACT_CLARIFICATION -> actions.add("CONNECT_CLARIFICATION_FACT_STORE_OR_REQUEST_USER_INPUT");
                case FACT_OUTBOX -> actions.add("CALL_JAVA_COMMAND_OUTBOX_WRITER_AFTER_GRAPH_CONFIRMATION");
                case FACT_WORKER_RECEIPT -> actions.add("WAIT_FOR_TASK_MANAGEMENT_RECEIPT_OR_RETRY_DISPATCHER");
                default -> actions.add("REVIEW_UNKNOWN_RESUME_FACT_TYPE");
            }
        }
        if (actions.isEmpty()) {
            actions.add("SERVER_FACT_TYPES_READY_FOR_PYTHON_RESUME_PREVIEW_BUT_DO_NOT_EXECUTE_TOOL_DIRECTLY");
        }
        return List.copyOf(actions);
    }

    /**
     * 构造生产就绪解释。
     *
     * <p>locator index 已经有内存版，因此旧的“完全缺少 checkpoint-to-facts discovery”需要收敛成更准确的边界：
     * 当前具备短期内存索引，但还缺 MySQL/审计级 durable index、clarification store、receipt 持久化索引和服务间强认证。</p>
     */
    static Map<String, Object> productionReadiness() {
        return Map.of(
                "currentMode", "CONTROL_PLANE_FACT_BUNDLE_PREVIEW_ONLY",
                "currentLocatorIndexMode", "IN_MEMORY_CHECKPOINT_THREAD_TO_FACT_LOCATOR_INDEX",
                "missingProductionRequirements", List.of(
                        "MYSQL_DURABLE_CHECKPOINT_THREAD_LOCATOR_INDEX",
                        "CLARIFICATION_FACT_PROVIDER",
                        "WORKER_RECEIPT_PERSISTENT_INDEX",
                        "SERVICE_ACCOUNT_SIGNATURE_OR_MTLS",
                        "PROMETHEUS_LOW_CARDINALITY_METRICS_FOR_RESUME_FACT_QUERY",
                        "AUDIT_EVENT_FOR_RESUME_FACT_BUNDLE_QUERY"
                )
        );
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
