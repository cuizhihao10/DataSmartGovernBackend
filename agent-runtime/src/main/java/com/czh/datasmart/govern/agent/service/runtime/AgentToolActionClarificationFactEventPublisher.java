/**
 * @Author : Cui
 * @Date: 2026/06/18 00:00
 * @Description DataSmart Govern Backend - AgentToolActionClarificationFactEventPublisher.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 澄清事实低敏 runtime event 发布器。
 *
 * <p>在真实 Agent Host 中，Human-in-the-loop 不只是“用户回了一句话”，它会影响后续执行图是否能从
 * interruption/checkpoint 继续推进。为了让管理台、审计台、WebSocket timeline 和后续 A2A/MCP 适配层
 * 能安全地观察这一步，本发布器会在澄清事实写入 Store 后追加一条低敏 runtime event。</p>
 *
 * <p>为什么单独拆成 Publisher：</p>
 * <p>1. {@link AgentToolActionClarificationFactRegistrationService} 负责事实登记、权限边界和 TTL 校验；
 * timeline 事件属于观测支路，继续塞进登记服务会让主流程膨胀。</p>
 * <p>2. 未来 runtime event projection 可能从内存切到 MySQL、ClickHouse、OpenSearch 或审计中心，
 * Publisher 作为隔离层可以让登记逻辑不关心投影底座。</p>
 * <p>3. 事件发布失败不应该阻断澄清事实登记。用户已经完成澄清时，最重要的是服务端 host fact 可回查；
 * timeline 暂时失败只应记录日志和后续补偿。</p>
 *
 * <p>敏感边界：本事件只保存状态、布尔值、低敏 code、计数、scope 摘要和工具 code。它不保存
 * clarificationFactId 原文、用户澄清正文、prompt、SQL、arguments、payload、样本数据、模型输出、
 * 凭证、token、内部 endpoint 或工具结果正文。</p>
 */
@Component
@RequiredArgsConstructor
public class AgentToolActionClarificationFactEventPublisher {

    public static final String SCHEMA_VERSION =
            "datasmart.agent-runtime.tool-action-clarification-fact-event.v1";
    public static final String SOURCE = "JAVA_AGENT_RUNTIME";
    public static final String EVENT_TYPE =
            "agent.tool_action.clarification_fact.recorded";
    public static final String PAYLOAD_POLICY =
            "LOW_SENSITIVE_CLARIFICATION_FACT_METADATA_ONLY_NO_FACT_ID_NO_USER_CONTENT";

    private static final Logger log =
            LoggerFactory.getLogger(AgentToolActionClarificationFactEventPublisher.class);
    private static final int MAX_CODE_COUNT = 12;

    private final AgentRuntimeEventProjectionStore projectionStore;

    /**
     * 发布澄清事实登记事件。
     *
     * @param record 已写入 Store 的澄清事实低敏记录。
     * @param accessContext gateway/permission-admin 传入的可信访问上下文。
     * @return 发布结果。调用方可以用于单元测试或内部诊断，但业务响应不应依赖该结果。
     */
    public PublishResult publish(AgentToolActionClarificationFactRecord record,
                                 AgentRuntimeEventQueryAccessContext accessContext) {
        if (record == null || !record.indexable()) {
            return PublishResult.skipped("NON_INDEXABLE_CLARIFICATION_FACT");
        }
        AgentRuntimeEventProjectionRecord projectionRecord = toProjectionRecord(record, accessContext);
        try {
            boolean appended = projectionStore.append(projectionRecord);
            return new PublishResult(true, appended, false,
                    projectionRecord.identityKey(), projectionRecord.eventType(), null);
        } catch (RuntimeException exception) {
            /*
             * runtime event 是观测支路，失败时不能把澄清事实登记整体回滚。这里也只记录异常类型，
             * 不记录异常 message，避免底层 JDBC/序列化异常把 SQL、连接串、内部表名细节或上下文带入日志。
             */
            log.warn("Agent clarification fact event publish failed, eventType={}, errorType={}",
                    EVENT_TYPE, exception.getClass().getSimpleName());
            return new PublishResult(true, false, true,
                    projectionRecord.identityKey(), projectionRecord.eventType(),
                    exception.getClass().getSimpleName());
        }
    }

    private AgentRuntimeEventProjectionRecord toProjectionRecord(AgentToolActionClarificationFactRecord record,
                                                                 AgentRuntimeEventQueryAccessContext accessContext) {
        Instant now = record.updatedAt() == null ? Instant.now() : record.updatedAt();
        return new AgentRuntimeEventProjectionRecord(
                identityKey(record),
                SCHEMA_VERSION,
                SOURCE,
                EVENT_TYPE,
                stage(record, now),
                message(record, now),
                severity(record, now),
                record.tenantId(),
                record.projectId(),
                record.actorId(),
                accessContext == null ? null : accessContext.traceId(),
                record.runId(),
                record.sessionId(),
                null,
                now,
                now,
                now,
                attributes(record, accessContext, now)
        );
    }

    private Map<String, Object> attributes(AgentToolActionClarificationFactRecord record,
                                           AgentRuntimeEventQueryAccessContext accessContext,
                                           Instant now) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("snapshotType", "TOOL_ACTION_CLARIFICATION_FACT");
        attributes.put("schemaVersion", SCHEMA_VERSION);
        attributes.put("payloadPolicy", PAYLOAD_POLICY);
        attributes.put("eventPayloadPolicy", PAYLOAD_POLICY);
        attributes.put("clarificationFactIdPresent", true);
        attributes.put("clarificationFactFingerprint", shortSha256(record.clarificationFactId()));
        attributes.put("status", safeCode(record.status()));
        attributes.put("available", record.statusAvailable() && !record.expiredAt(now));
        attributes.put("expired", record.expiredAt(now));
        attributes.put("expiresAtPresent", record.expiresAt() != null);
        attributes.put("runIdPresent", hasText(record.runId()));
        attributes.put("sessionIdPresent", hasText(record.sessionId()));
        attributes.put("commandIdPresent", hasText(record.commandId()));
        attributes.put("toolCode", safeCode(record.toolCode()));
        attributes.put("requestedPolicyVersion", safeCode(record.requestedPolicyVersion()));
        attributes.put("evidenceCodes", safeCodes(record.evidenceCodes()));
        attributes.put("issueCodes", safeCodes(record.issueCodes()));
        attributes.put("evidenceCodeCount", safeCodes(record.evidenceCodes()).size());
        attributes.put("issueCodeCount", safeCodes(record.issueCodes()).size());
        attributes.put("securityBoundary", securityBoundary(accessContext));
        attributes.put("recommendedActions", recommendedActions(record, now));
        return Collections.unmodifiableMap(attributes);
    }

    private Map<String, Object> securityBoundary(AgentRuntimeEventQueryAccessContext accessContext) {
        Map<String, Object> boundary = new LinkedHashMap<>();
        boundary.put("identityPresent", accessContext != null && accessContext.hasIdentity());
        boundary.put("actorRole", accessContext == null ? "UNKNOWN" : safeCode(accessContext.normalizedRole()));
        boundary.put("dataScopeLevel", accessContext == null ? "" : safeCode(accessContext.normalizedDataScopeLevel()));
        boundary.put("explicitProjectScope", accessContext != null && accessContext.explicitProjectScope());
        boundary.put("authorizedProjectCount", accessContext == null
                ? 0
                : accessContext.authorizedProjectIdsAsStrings().size());
        return Collections.unmodifiableMap(boundary);
    }

    private List<String> recommendedActions(AgentToolActionClarificationFactRecord record, Instant now) {
        if (record.expiredAt(now)) {
            return List.of("重新向用户或上游 Agent 发起澄清，过期澄清事实不能继续用于恢复预检。");
        }
        if (AgentToolActionClarificationFactRecord.STATUS_REVOKED.equals(record.status())) {
            return List.of("尊重用户撤销结果，重新生成需要澄清的字段列表，不要复用旧 clarificationFactId。");
        }
        if (AgentToolActionClarificationFactRecord.STATUS_REJECTED.equals(record.status())) {
            return List.of("检查 issueCodes 并回到计划阶段，拒绝的澄清事实不能作为执行授权或参数补全依据。");
        }
        return List.of("澄清事实只表示 CLARIFICATION_FACT 可回查，真实工具 resume 仍必须经过 approval、outbox、worker receipt 和审计闭环。");
    }

    private String identityKey(AgentToolActionClarificationFactRecord record) {
        String runToken = safeToken(record.runId(), "no-run");
        String sessionToken = safeToken(record.sessionId(), "no-session");
        String fingerprintMaterial = String.join("|",
                Objects.toString(record.clarificationFactId(), ""),
                Objects.toString(record.status(), ""),
                Objects.toString(record.tenantId(), ""),
                Objects.toString(record.projectId(), ""),
                Objects.toString(record.actorId(), ""),
                Objects.toString(record.commandId(), ""),
                Objects.toString(record.toolCode(), "")
        );
        return "clarification-fact:" + runToken + ":" + sessionToken + ":"
                + shortSha256(fingerprintMaterial);
    }

    private String stage(AgentToolActionClarificationFactRecord record, Instant now) {
        if (record.expiredAt(now)) {
            return "clarification_fact_expired";
        }
        if (AgentToolActionClarificationFactRecord.STATUS_REVOKED.equals(record.status())) {
            return "clarification_fact_revoked";
        }
        if (AgentToolActionClarificationFactRecord.STATUS_REJECTED.equals(record.status())) {
            return "clarification_fact_rejected";
        }
        return "clarification_fact_available";
    }

    private String severity(AgentToolActionClarificationFactRecord record, Instant now) {
        return record.statusAvailable() && !record.expiredAt(now) ? "audit" : "warning";
    }

    private String message(AgentToolActionClarificationFactRecord record, Instant now) {
        return "澄清事实已登记：status=" + record.status()
                + "，available=" + (record.statusAvailable() && !record.expiredAt(now))
                + "，runIdPresent=" + hasText(record.runId())
                + "，sessionIdPresent=" + hasText(record.sessionId())
                + "，commandIdPresent=" + hasText(record.commandId())
                + "。";
    }

    private List<String> safeCodes(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .map(this::safeCode)
                .filter(this::hasText)
                .limit(MAX_CODE_COUNT)
                .toList();
    }

    private String safeCode(String value) {
        String text = text(value);
        if (text == null) {
            return null;
        }
        String normalized = text.replaceAll("[^A-Za-z0-9_.:\\-]", "_");
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
            return Integer.toHexString(Objects.toString(value, "").hashCode()).toLowerCase(Locale.ROOT);
        }
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 澄清事实 runtime event 发布结果。
     *
     * @param attempted 是否尝试发布。
     * @param appended true 表示成功写入新事件；false 可能是幂等重复或发布失败。
     * @param failed true 表示发布支路失败，但主登记流程仍可继续返回。
     * @param identityKey 低敏幂等键，内部诊断使用，不包含 clarificationFactId 原文。
     * @param eventType 事件类型。
     * @param failureType 失败类型或跳过原因。
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
