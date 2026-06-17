/**
 * @Author : Cui
 * @Date: 2026/06/17 00:00
 * @Description DataSmart Govern Backend - AgentToolActionResumeFactBundleResponseSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.config.AgentToolActionResumeFactBundleProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionResumeFactBundleQueryRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionResumeFactView;

import java.util.ArrayList;
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
     * <p>这里不再写死“当前只有内存 locator index”。5.77 之后 locator index 已支持 memory/mysql 两种模式，
     * 因此响应应根据配置告诉调用方当前运行介质。这样 Python Runtime、运维台和学习者看到的不是过期说明，
     * 而是“当前环境到底是单机学习模式，还是已经切到 MySQL durable 控制面索引”。</p>
     */
    static Map<String, Object> productionReadiness(AgentToolActionResumeFactBundleProperties properties) {
        String locatorIndexStore = properties == null ? "memory" : text(properties.getLocatorIndexStore());
        boolean mysqlLocatorIndex = "mysql".equalsIgnoreCase(locatorIndexStore);
        boolean diagnosticEventEnabled = properties == null
                || !Boolean.FALSE.equals(properties.getDiagnosticEventEnabled());
        List<String> missingRequirements = new ArrayList<>();
        if (!mysqlLocatorIndex) {
            missingRequirements.add("MYSQL_DURABLE_CHECKPOINT_THREAD_LOCATOR_INDEX");
        }
        missingRequirements.add("CLARIFICATION_FACT_PROVIDER");
        missingRequirements.add("WORKER_RECEIPT_PERSISTENT_INDEX");
        missingRequirements.add("SERVICE_ACCOUNT_SIGNATURE_OR_MTLS");
        missingRequirements.add("PROMETHEUS_LOW_CARDINALITY_METRICS_FOR_RESUME_FACT_QUERY");
        missingRequirements.add("DURABLE_AUDIT_EVENT_STORE_FOR_RESUME_FACT_BUNDLE_QUERY");
        if (!diagnosticEventEnabled) {
            missingRequirements.add("RUNTIME_EVENT_DIAGNOSTIC_SNAPSHOT_FOR_RESUME_FACT_BUNDLE_QUERY");
        }
        return Map.of(
                "currentMode", "CONTROL_PLANE_FACT_BUNDLE_PREVIEW_ONLY",
                "currentLocatorIndexMode", mysqlLocatorIndex
                        ? "MYSQL_DURABLE_CHECKPOINT_THREAD_TO_FACT_LOCATOR_INDEX"
                        : "IN_MEMORY_CHECKPOINT_THREAD_TO_FACT_LOCATOR_INDEX",
                "diagnosticEventMode", diagnosticEventEnabled
                        ? "LOW_SENSITIVE_RUNTIME_EVENT_DIAGNOSTIC_SNAPSHOT"
                        : "DISABLED",
                "missingProductionRequirements", List.copyOf(missingRequirements)
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
