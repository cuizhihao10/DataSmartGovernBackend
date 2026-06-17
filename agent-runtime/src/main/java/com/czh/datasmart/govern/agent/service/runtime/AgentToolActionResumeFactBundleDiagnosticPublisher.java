/**
 * @Author : Cui
 * @Date: 2026/06/17 00:00
 * @Description DataSmart Govern Backend - AgentToolActionResumeFactBundleDiagnosticPublisher.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionResumeFactBundleQueryRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionResumeFactBundleResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionResumeFactOutboxSummaryView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionResumeFactReceiptSummaryView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionResumeFactView;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 恢复事实包诊断事件发布器。
 *
 * <p>恢复事实包查询本身仍然是 preview-only：不执行工具、不写 command outbox、不派发 worker、
 * 不修改 checkpoint。这个发布器只额外写入一条低敏 runtime event projection，用于让管理台、审计台和
 * WebSocket timeline 能看到“本次恢复预检到底卡在哪个事实类型”。</p>
 *
 * <p>为什么需要独立 publisher，而不是把逻辑直接塞进 {@link AgentToolActionResumeFactBundleService}：</p>
 * <p>1. fact bundle service 应专注聚合 approval/outbox/receipt 等事实源；</p>
 * <p>2. timeline 事件是展示和审计支路，未来可能迁移到 MySQL/ClickHouse/审计中心；</p>
 * <p>3. 独立类能控制行数与职责边界，避免核心 service 因诊断字段不断膨胀。</p>
 *
 * <p>敏感边界：本事件只保存事实类型、状态、计数、低敏 issue/evidence code、scope 摘要和 locator 是否命中。
 * 它不会保存 approvalFactId、clarificationFactId、outboxId、payloadReference、targetEndpoint、
 * prompt、SQL、工具参数、样本数据、模型输出、凭证或内部接口正文。</p>
 */
@Component
@RequiredArgsConstructor
public class AgentToolActionResumeFactBundleDiagnosticPublisher {

    public static final String SCHEMA_VERSION =
            "datasmart.agent-runtime.tool-action-resume-fact-bundle-diagnostic.v1";
    public static final String SOURCE = "JAVA_AGENT_RUNTIME";
    public static final String EVENT_TYPE =
            "agent.tool_action.resume_fact_bundle.diagnostics_recorded";
    public static final String PAYLOAD_POLICY =
            "FACT_TYPE_STATUS_COUNTS_ONLY_NO_FACT_VALUES_NO_PAYLOAD_BODY";

    private static final Logger log =
            LoggerFactory.getLogger(AgentToolActionResumeFactBundleDiagnosticPublisher.class);
    private static final int MAX_CODE_COUNT = 12;
    private static final int MAX_ACTION_COUNT = 8;

    private final AgentRuntimeEventProjectionStore projectionStore;

    /**
     * 将事实包响应转换成低敏 timeline 诊断事件。
     *
     * @param request 已经经过 locator index 补齐后的查询请求；只读取低敏定位符和存在性，不回显敏感 ID。
     * @param response fact bundle 的服务端采信结果；只读取事实类型、状态和计数。
     * @param accessContext 当前请求的数据范围上下文，用于生成安全边界摘要。
     * @return 发布结果。即使发布失败，也不应该让恢复预检主查询失败。
     */
    public PublishResult publish(AgentToolActionResumeFactBundleQueryRequest request,
                                 AgentToolActionResumeFactBundleResponse response,
                                 AgentRuntimeEventQueryAccessContext accessContext) {
        if (request == null || response == null) {
            return PublishResult.skipped("EMPTY_REQUEST_OR_RESPONSE");
        }
        AgentRuntimeEventProjectionRecord record = toProjectionRecord(request, response, accessContext);
        try {
            boolean appended = projectionStore.append(record);
            return new PublishResult(true, appended, false, record.identityKey(), record.eventType(), null);
        } catch (RuntimeException exception) {
            /*
             * 诊断事件是可观测性支路，不能因为内存投影窗口、后续 MySQL 审计表或序列化问题，
             * 反过来阻断 Python Runtime 的恢复事实预检。日志只记录异常类型和事件类型，不写请求定位符。
             */
            log.warn("Agent resume fact bundle diagnostic event publish failed, eventType={}, errorType={}",
                    EVENT_TYPE, exception.getClass().getSimpleName());
            return new PublishResult(true, false, true, record.identityKey(), record.eventType(),
                    exception.getClass().getSimpleName());
        }
    }

    private AgentRuntimeEventProjectionRecord toProjectionRecord(AgentToolActionResumeFactBundleQueryRequest request,
                                                                 AgentToolActionResumeFactBundleResponse response,
                                                                 AgentRuntimeEventQueryAccessContext accessContext) {
        Instant now = response.generatedAt() == null ? Instant.now() : response.generatedAt();
        return new AgentRuntimeEventProjectionRecord(
                identityKey(request, response, accessContext),
                SCHEMA_VERSION,
                SOURCE,
                EVENT_TYPE,
                stage(response),
                message(response),
                severity(response),
                stringValue(firstLong(request.tenantId(), accessContext == null ? null : accessContext.tenantId())),
                stringValue(request.projectId()),
                firstText(request.actorId(), accessContext == null || accessContext.actorId() == null
                        ? null
                        : String.valueOf(accessContext.actorId())),
                accessContext == null ? null : accessContext.traceId(),
                text(request.runId()),
                text(request.sessionId()),
                null,
                now,
                now,
                now,
                attributes(request, response, accessContext)
        );
    }

    private Map<String, Object> attributes(AgentToolActionResumeFactBundleQueryRequest request,
                                           AgentToolActionResumeFactBundleResponse response,
                                           AgentRuntimeEventQueryAccessContext accessContext) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("snapshotType", "TOOL_ACTION_RESUME_FACT_BUNDLE_DIAGNOSTIC");
        attributes.put("schemaVersion", response.schemaVersion());
        attributes.put("previewOnly", Boolean.TRUE.equals(response.previewOnly()));
        attributes.put("queryBoundary", response.queryBoundary());
        attributes.put("payloadPolicy", PAYLOAD_POLICY);
        attributes.put("securityBoundary", securityBoundary(accessContext));
        attributes.put("checkpointIdPresent", Boolean.TRUE.equals(locator(response, "checkpointIdPresent")));
        attributes.put("threadIdPresent", Boolean.TRUE.equals(locator(response, "threadIdPresent")));
        attributes.put("sessionIdPresent", hasText(request.sessionId()));
        attributes.put("runIdPresent", hasText(request.runId()));
        attributes.put("commandIdPresent", hasText(request.commandId()));
        attributes.put("outboxIdPresent", Boolean.TRUE.equals(locator(response, "outboxIdPresent")));
        attributes.put("approvalFactIdPresent", Boolean.TRUE.equals(locator(response, "approvalFactIdPresent")));
        attributes.put("clarificationFactIdPresent",
                Boolean.TRUE.equals(locator(response, "clarificationFactIdPresent")));
        attributes.put("toolCode", safeCode(request.toolCode()));
        attributes.put("locatorIndexHit", Boolean.TRUE.equals(locator(response, "locatorIndexHit")));
        attributes.put("locatorIndexEvidenceCodes", safeCodes(locatorCodes(response)));
        attributes.put("requiredFactTypes", safeCodes(response.requiredFactTypes()));
        attributes.put("availableFactTypes", safeCodes(response.availableFactTypes()));
        attributes.put("missingFactTypes", safeCodes(response.missingFactTypes()));
        attributes.put("rejectedFactTypes", safeCodes(response.rejectedFactTypes()));
        attributes.put("requiredFactTypeCount", response.requiredFactTypes().size());
        attributes.put("availableFactTypeCount", response.availableFactTypes().size());
        attributes.put("missingFactTypeCount", response.missingFactTypes().size());
        attributes.put("rejectedFactTypeCount", response.rejectedFactTypes().size());
        attributes.put("factSummaries", factSummaries(response.facts()));
        attributes.put("outboxSummary", outboxSummary(response.outboxSummary()));
        attributes.put("receiptSummary", receiptSummary(response.receiptSummary()));
        attributes.put("recommendedActions", safeActions(response.recommendedActions()));
        attributes.put("productionReadiness", productionReadinessSummary(response.productionReadiness()));
        attributes.put("eventPayloadPolicy", PAYLOAD_POLICY);
        return Collections.unmodifiableMap(attributes);
    }

    private Map<String, Object> securityBoundary(AgentRuntimeEventQueryAccessContext accessContext) {
        Map<String, Object> boundary = new LinkedHashMap<>();
        boundary.put("identityPresent", accessContext != null && accessContext.hasIdentity());
        boundary.put("actorRole", accessContext == null ? "UNKNOWN" : accessContext.normalizedRole());
        boundary.put("dataScopeLevel", accessContext == null ? "" : accessContext.normalizedDataScopeLevel());
        boundary.put("explicitProjectScope", accessContext != null && accessContext.explicitProjectScope());
        boundary.put("authorizedProjectCount", accessContext == null
                ? 0
                : accessContext.authorizedProjectIdsAsStrings().size());
        /*
         * 这里刻意只写 boolean/count，不写 authorizedProjectIds 原始集合。
         * 项目 ID 已经是控制面定位符，具体列表应由权限中心和查询过滤层掌握，timeline 只需要解释范围是否生效。
         */
        return Collections.unmodifiableMap(boundary);
    }

    private List<Map<String, Object>> factSummaries(List<AgentToolActionResumeFactView> facts) {
        if (facts == null || facts.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (AgentToolActionResumeFactView fact : facts) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("factType", safeCode(fact.factType()));
            item.put("source", safeCode(fact.source()));
            item.put("status", safeCode(fact.status()));
            item.put("available", Boolean.TRUE.equals(fact.available()));
            item.put("rejected", Boolean.TRUE.equals(fact.rejected()));
            item.put("retryable", Boolean.TRUE.equals(fact.retryable()));
            item.put("evidenceCodeCount", safeCodes(fact.evidenceCodes()).size());
            item.put("issueCodes", safeCodes(fact.issueCodes()));
            summaries.add(Collections.unmodifiableMap(item));
        }
        return List.copyOf(summaries);
    }

    private Map<String, Object> outboxSummary(AgentToolActionResumeFactOutboxSummaryView summary) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("present", summary != null);
        if (summary != null) {
            item.put("status", safeCode(summary.status()));
            item.put("commandType", safeCode(summary.commandType()));
            item.put("consumerService", safeCode(summary.consumerService()));
            item.put("toolCode", safeCode(summary.toolCode()));
            item.put("targetService", safeCode(summary.targetService()));
            item.put("attemptCount", summary.attemptCount());
            item.put("payloadReferencePresent", Boolean.TRUE.equals(summary.payloadReferencePresent()));
            item.put("payloadTruncated", Boolean.TRUE.equals(summary.payloadTruncated()));
        }
        return Collections.unmodifiableMap(item);
    }

    private Map<String, Object> receiptSummary(AgentToolActionResumeFactReceiptSummaryView summary) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("present", summary != null);
        if (summary != null) {
            item.put("receiptCount", nonNegative(summary.receiptCount()));
            item.put("commandIdMatched", Boolean.TRUE.equals(summary.commandIdMatched()));
            item.put("latestOutcome", safeCode(summary.latestOutcome()));
            item.put("latestTaskStatus", safeCode(summary.latestTaskStatus()));
            item.put("latestPreCheckPassed", Boolean.TRUE.equals(summary.latestPreCheckPassed()));
            item.put("latestSideEffectExecuted", Boolean.TRUE.equals(summary.latestSideEffectExecuted()));
            item.put("latestErrorCode", safeCode(summary.latestErrorCode()));
            item.put("payloadPolicy", safeCode(summary.payloadPolicy()));
        }
        return Collections.unmodifiableMap(item);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> productionReadinessSummary(Map<String, Object> productionReadiness) {
        Map<String, Object> item = new LinkedHashMap<>();
        if (productionReadiness == null || productionReadiness.isEmpty()) {
            return Collections.unmodifiableMap(item);
        }
        item.put("currentMode", safeCode(Objects.toString(productionReadiness.get("currentMode"), null)));
        item.put("currentLocatorIndexMode",
                safeCode(Objects.toString(productionReadiness.get("currentLocatorIndexMode"), null)));
        item.put("diagnosticEventMode",
                safeCode(Objects.toString(productionReadiness.get("diagnosticEventMode"), null)));
        Object missing = productionReadiness.get("missingProductionRequirements");
        if (missing instanceof List<?> list) {
            item.put("missingProductionRequirements", safeCodes((List<?>) list));
            item.put("missingProductionRequirementCount", list.size());
        } else {
            item.put("missingProductionRequirements", List.of());
            item.put("missingProductionRequirementCount", 0);
        }
        return Collections.unmodifiableMap(item);
    }

    private String identityKey(AgentToolActionResumeFactBundleQueryRequest request,
                               AgentToolActionResumeFactBundleResponse response,
                               AgentRuntimeEventQueryAccessContext accessContext) {
        String runToken = safeToken(firstText(request.runId(), "no-run"), "no-run");
        String sessionToken = safeToken(firstText(request.sessionId(), "no-session"), "no-session");
        String scope = String.join("|",
                stringValue(firstLong(request.tenantId(), accessContext == null ? null : accessContext.tenantId())),
                stringValue(request.projectId()),
                firstText(request.actorId(), accessContext == null || accessContext.actorId() == null
                        ? null
                        : String.valueOf(accessContext.actorId()))
        );
        String fingerprintMaterial = String.join("|",
                scope,
                text(request.checkpointId()),
                text(request.threadId()),
                text(request.commandId()),
                text(request.toolCode()),
                String.valueOf(locator(response, "locatorIndexHit")),
                String.join(",", response.availableFactTypes()),
                String.join(",", response.missingFactTypes()),
                String.join(",", response.rejectedFactTypes())
        );
        return "resume-fact-bundle-diagnostic:" + runToken + ":" + sessionToken + ":"
                + shortSha256(fingerprintMaterial);
    }

    private String stage(AgentToolActionResumeFactBundleResponse response) {
        if (!response.rejectedFactTypes().isEmpty()) {
            return "resume_fact_bundle_rejected";
        }
        if (!response.missingFactTypes().isEmpty()) {
            return "resume_fact_bundle_missing_facts";
        }
        return "resume_fact_bundle_ready_preview_only";
    }

    private String severity(AgentToolActionResumeFactBundleResponse response) {
        if (!response.rejectedFactTypes().isEmpty()) {
            return "warning";
        }
        if (!response.missingFactTypes().isEmpty()) {
            return "info";
        }
        return "audit";
    }

    private String message(AgentToolActionResumeFactBundleResponse response) {
        return "恢复事实包诊断：已采信 " + response.availableFactTypes().size()
                + " 个，缺失 " + response.missingFactTypes().size()
                + " 个，拒绝 " + response.rejectedFactTypes().size()
                + " 个，locatorIndexHit="
                + Boolean.TRUE.equals(locator(response, "locatorIndexHit")) + "。";
    }

    private Object locator(AgentToolActionResumeFactBundleResponse response, String key) {
        return response.requestedLocator().get(key);
    }

    @SuppressWarnings("unchecked")
    private List<?> locatorCodes(AgentToolActionResumeFactBundleResponse response) {
        Object value = locator(response, "locatorIndexEvidenceCodes");
        return value instanceof List<?> list ? list : List.of();
    }

    private List<String> safeActions(List<String> actions) {
        if (actions == null || actions.isEmpty()) {
            return List.of();
        }
        return actions.stream()
                .filter(this::hasText)
                .limit(MAX_ACTION_COUNT)
                .map(this::safeCode)
                .toList();
    }

    private List<String> safeCodes(List<?> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(value -> safeCode(String.valueOf(value)))
                .filter(this::hasText)
                .limit(MAX_CODE_COUNT)
                .toList();
    }

    private String safeCode(String value) {
        String text = text(value);
        if (text == null) {
            return null;
        }
        /*
         * code 字段只允许保留适合 timeline 展示和聚合的低敏字符。
         * 如果上游错误地把 SQL、URL、prompt 或自然语言正文塞进 code，这里会被压缩成安全 token，
         * 避免诊断事件成为新的泄露通道。
         */
        String normalized = text.replaceAll("[^A-Za-z0-9_.:-]", "_");
        return normalized.length() <= 160 ? normalized : normalized.substring(0, 160);
    }

    private String safeToken(String value, String fallback) {
        String token = safeCode(value);
        return token == null || token.isBlank() ? fallback : token;
    }

    private String shortSha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(Objects.toString(value, "").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes).substring(0, 16);
        } catch (NoSuchAlgorithmException exception) {
            /*
             * SHA-256 是 JDK 标准算法，正常不会缺失。保留 fallback 是为了让 identityKey 生成逻辑在极端环境下仍可用。
             */
            return Integer.toHexString(Objects.toString(value, "").hashCode()).toLowerCase(Locale.ROOT);
        }
    }

    private Long firstLong(Long first, Long fallback) {
        return first != null ? first : fallback;
    }

    private String firstText(String first, String fallback) {
        return hasText(first) ? first.trim() : text(fallback);
    }

    private String stringValue(Long value) {
        return value == null ? null : String.valueOf(value);
    }

    private int nonNegative(Integer value) {
        return value == null || value < 0 ? 0 : value;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    /**
     * 诊断事件发布结果。
     *
     * @param attempted 是否尝试发布；配置关闭或输入为空时为 false。
     * @param appended true 表示成功写入一条新 timeline 事件；false 可能是幂等重复或发布失败。
     * @param failed true 表示发布支路失败，但主查询仍可返回。
     * @param identityKey 低敏事件幂等键，便于测试和内部排障。
     * @param eventType 事件类型。
     * @param failureType 失败类型，只记录异常类名，不记录异常消息，避免日志泄露上下文。
     */
    public record PublishResult(
            boolean attempted,
            boolean appended,
            boolean failed,
            String identityKey,
            String eventType,
            String failureType
    ) {

        static PublishResult skipped(String reason) {
            return new PublishResult(false, false, false, null, EVENT_TYPE, reason);
        }
    }
}
