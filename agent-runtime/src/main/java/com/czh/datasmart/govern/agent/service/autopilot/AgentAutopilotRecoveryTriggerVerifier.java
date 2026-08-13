/**
 * @Author : Cui
 * @Date: 2026/08/11 19:45
 * @Description DataSmart Govern Backend - AgentAutopilotRecoveryTriggerVerifier.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.autopilot;

import com.czh.datasmart.govern.agent.service.session.AgentDelegationRecord;
import com.czh.datasmart.govern.agent.service.session.AgentRunRecord;
import com.czh.datasmart.govern.agent.service.session.AgentSessionRecord;
import com.czh.datasmart.govern.agent.service.session.AgentSessionStore;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 重新验证 Autopilot Kafka 触发器的持久授权与双主体范围。
 *
 * <p>生产 Kafka 采用至少一次投递，消息也可能被错误路由或重放。因此消费者不能只验证 JSON 格式，
 * 必须重新加载 session、root Run 和 delegation，并逐项对照事件中的 tenant/application/project/user/
 * agent/delegation。授权快照 digest 只证明 data-sync 发送的白名单字段未被改写；真正执行权仍来自
 * root Run 中由用户首次确认后持久化的 ``autopilotAuthorization``。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentAutopilotRecoveryTriggerVerifier {

    private static final String SCHEMA_VERSION = "datasmart.autopilot.recovery-trigger.v1";
    private static final String RUN_AUTHORIZATION_VARIABLE = "autopilotAuthorization";

    private final AgentSessionStore sessionStore;
    private final ObjectMapper objectMapper;

    /**
     * 验证事件并返回只能由本服务构造的可信工作包。
     *
     * @param event 已完成 Jackson 强类型反序列化的 Kafka 事件
     * @return 包含持久 session/run/authorization 与统一 UTC 时间的可信触发器
     */
    public AgentAutopilotVerifiedRecoveryTrigger verify(AgentAutopilotRecoveryTriggerEvent event) {
        requireEvent(event);
        AgentSessionRecord session = sessionStore.findById(event.rootSessionId())
                .orElseThrow(() -> forbidden("AUTOPILOT_ROOT_SESSION_NOT_FOUND"));
        AgentRunRecord rootRun = session.getRuns().stream()
                .filter(run -> Objects.equals(run.getRunId(), event.rootRunId()))
                .findFirst()
                .orElseThrow(() -> forbidden("AUTOPILOT_ROOT_RUN_NOT_FOUND"));
        AgentDelegationRecord delegation = session.getDelegation();
        if (delegation == null || !delegation.active(LocalDateTime.now())) {
            throw forbidden("AUTOPILOT_DELEGATION_INACTIVE");
        }
        verifySessionScope(event, session, delegation);
        verifySnapshotDigest(event);

        Object runAuthorization = rootRun.getVariables().get(RUN_AUTHORIZATION_VARIABLE);
        if (!(runAuthorization instanceof Map<?, ?> rawRunSnapshot)) {
            throw forbidden("AUTOPILOT_RUN_AUTHORIZATION_NOT_FOUND");
        }
        Map<String, Object> runSnapshot = copyMap(rawRunSnapshot);
        verifyPersistedSnapshotMatchesEvent(runSnapshot, event.authorizationSnapshot());
        AgentAutopilotAuthorizationSnapshot authorization = restoreAuthorization(runSnapshot);
        verifyAuthorizationScope(event, authorization);

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime deadlineAt = parseTime(event.deadlineAt(), "deadlineAt");
        if (!deadlineAt.isAfter(now) || !authorization.expiresAt().isAfter(now)
                || deadlineAt.isAfter(authorization.expiresAt())) {
            throw forbidden("AUTOPILOT_DEADLINE_OR_AUTHORIZATION_EXPIRED");
        }
        OffsetDateTime recoveryStartedAt = deadlineAt.minusMinutes(authorization.maxTotalDurationMinutes());
        return new AgentAutopilotVerifiedRecoveryTrigger(
                event, session, rootRun, authorization, deadlineAt, recoveryStartedAt);
    }

    /** 在任何仓储或模型调用前验证事件的固定合同、循环预算和指纹格式。 */
    private void requireEvent(AgentAutopilotRecoveryTriggerEvent event) {
        if (event == null || !SCHEMA_VERSION.equals(event.schemaVersion())
                || blank(event.eventId()) || blank(event.rootSessionId()) || blank(event.rootRunId())
                || event.tenantId() == null || event.applicationId() == null || event.projectId() == null
                || event.syncTaskId() == null || event.rootExecutionId() == null
                || event.currentExecutionId() == null || event.cycle() < 1
                || event.maxRecoveryCycles() < 1 || event.cycle() > event.maxRecoveryCycles()
                || !sha256(event.errorFingerprint()) || event.repeatedErrorCount() < 0
                || (event.previousRepairFingerprint() != null
                && !sha256(event.previousRepairFingerprint()))) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "Autopilot recovery trigger contract is invalid");
        }
    }

    /** 对照会话和委派中的双主体身份，拒绝跨租户、跨项目或伪造 Agent 的事件。 */
    private void verifySessionScope(AgentAutopilotRecoveryTriggerEvent event,
                                    AgentSessionRecord session,
                                    AgentDelegationRecord delegation) {
        if (!Objects.equals(session.getTenantId(), event.tenantId())
                || !Objects.equals(session.getApplicationId(), event.applicationId())
                || !Objects.equals(session.getProjectId(), event.projectId())
                || !Objects.equals(session.getActorId(), event.actorId())
                || !Objects.equals(session.getAgentId(), event.agentId())
                || !Objects.equals(delegation.getDelegationId(), event.delegationId())
                || !Objects.equals(delegation.getUserActorId(), event.userId())
                || !Objects.equals(delegation.getTenantId(), event.tenantId())
                || !Objects.equals(delegation.getProjectId(), event.projectId())) {
            throw forbidden("AUTOPILOT_SESSION_SCOPE_MISMATCH");
        }
    }

    /**
     * 使用 data-sync 生成事件时的字段顺序重新序列化授权快照并校验 SHA-256。
     *
     * <p>该 digest 防止 outbox/Kafka 传输中字段被改写，但不独立授予权限，所以校验通过后仍需与
     * root Run 的持久快照逐字段比较。</p>
     */
    private void verifySnapshotDigest(AgentAutopilotRecoveryTriggerEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event.authorizationSnapshot());
            String expected = "sha256:" + digest(json);
            if (!constantEquals(expected, event.authorizationSnapshotDigest())) {
                throw forbidden("AUTOPILOT_AUTHORIZATION_SNAPSHOT_DIGEST_MISMATCH");
            }
        } catch (JsonProcessingException exception) {
            throw forbidden("AUTOPILOT_AUTHORIZATION_SNAPSHOT_INVALID");
        }
    }

    /**
     * 比较 Run 持久授权与事件快照的安全关键字段。
     *
     * <p>事件可以包含 data-sync 为本地循环策略补充的 ``maxRepeatedErrorCount``，而早期 Run 快照没有
     * 该字段；因此这里只比较首次用户确认时已经签发的权威字段，不要求两个 Map 字节级完全相同。</p>
     */
    private void verifyPersistedSnapshotMatchesEvent(Map<String, Object> runSnapshot,
                                                      Map<String, Object> eventSnapshot) {
        List<String> fields = List.of(
                "policyId", "policyVersion", "state", "rootSessionId", "rootRunId",
                "tenantId", "applicationId", "projectId", "userId", "actorId", "agentId",
                "delegationId", "maxRecoveryCycles", "maxTotalDurationMinutes",
                "maxAutomaticRiskLevel", "allowedRecoveryActions", "requireApprovalFor",
                "issuedAt", "expiresAt", "policyDigest");
        for (String field : fields) {
            if (!equivalent(runSnapshot.get(field), eventSnapshot.get(field))) {
                throw forbidden("AUTOPILOT_PERSISTED_AUTHORIZATION_MISMATCH");
            }
        }
    }

    /** 从 Run JSONB 快照恢复强类型授权，后续策略层不再读取松散 Map。 */
    private AgentAutopilotAuthorizationSnapshot restoreAuthorization(Map<String, Object> snapshot) {
        return new AgentAutopilotAuthorizationSnapshot(
                requiredText(snapshot, "policyId"),
                requiredText(snapshot, "policyVersion"),
                requiredText(snapshot, "state"),
                requiredText(snapshot, "rootSessionId"),
                requiredText(snapshot, "rootRunId"),
                requiredLong(snapshot, "tenantId"),
                requiredLong(snapshot, "applicationId"),
                requiredLong(snapshot, "projectId"),
                requiredText(snapshot, "userId"),
                requiredText(snapshot, "actorId"),
                requiredText(snapshot, "agentId"),
                requiredText(snapshot, "delegationId"),
                requiredInt(snapshot, "maxRecoveryCycles"),
                requiredInt(snapshot, "maxTotalDurationMinutes"),
                requiredText(snapshot, "maxAutomaticRiskLevel").toUpperCase(Locale.ROOT),
                stringList(snapshot.get("allowedRecoveryActions")),
                stringList(snapshot.get("requireApprovalFor")),
                parseTime(requiredText(snapshot, "issuedAt"), "issuedAt"),
                parseTime(requiredText(snapshot, "expiresAt"), "expiresAt"),
                requiredText(snapshot, "policyDigest")
        );
    }

    /** 对照事件与恢复后的授权，避免合法快照被用于另一条任务失败消息。 */
    private void verifyAuthorizationScope(AgentAutopilotRecoveryTriggerEvent event,
                                          AgentAutopilotAuthorizationSnapshot authorization) {
        if (!"ACTIVE".equalsIgnoreCase(authorization.state())
                || !Objects.equals(authorization.rootSessionId(), event.rootSessionId())
                || !Objects.equals(authorization.rootRunId(), event.rootRunId())
                || !Objects.equals(authorization.tenantId(), event.tenantId())
                || !Objects.equals(authorization.applicationId(), event.applicationId())
                || !Objects.equals(authorization.projectId(), event.projectId())
                || !Objects.equals(authorization.userId(), event.userId())
                || !Objects.equals(authorization.actorId(), event.actorId())
                || !Objects.equals(authorization.agentId(), event.agentId())
                || !Objects.equals(authorization.delegationId(), event.delegationId())
                || authorization.maxRecoveryCycles() != event.maxRecoveryCycles()) {
            throw forbidden("AUTOPILOT_AUTHORIZATION_SCOPE_MISMATCH");
        }
    }

    /** 把 Jackson 的通配 Map 转为普通字符串键 Map，拒绝非字符串字段名。 */
    private Map<String, Object> copyMap(Map<?, ?> raw) {
        java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (!(key instanceof String text)) {
                throw forbidden("AUTOPILOT_AUTHORIZATION_FIELD_INVALID");
            }
            result.put(text, value);
        });
        return result;
    }

    /** 比较 JSON 标量或字符串数组，消除 Integer/Long 和集合实现差异。 */
    private boolean equivalent(Object left, Object right) {
        if (left instanceof Iterable<?> leftItems && right instanceof Iterable<?> rightItems) {
            List<String> leftValues = new ArrayList<>();
            leftItems.forEach(value -> leftValues.add(String.valueOf(value)));
            List<String> rightValues = new ArrayList<>();
            rightItems.forEach(value -> rightValues.add(String.valueOf(value)));
            return leftValues.equals(rightValues);
        }
        return Objects.equals(left, right) || Objects.equals(String.valueOf(left), String.valueOf(right));
    }

    /** 读取必填文本字段并去除前后空格。 */
    private String requiredText(Map<String, Object> values, String field) {
        Object value = values.get(field);
        if (value == null || String.valueOf(value).isBlank()) {
            throw forbidden("AUTOPILOT_AUTHORIZATION_FIELD_MISSING");
        }
        return String.valueOf(value).trim();
    }

    /** 读取 JSON 整数 ID，拒绝小数、负数和自由文本。 */
    private Long requiredLong(Map<String, Object> values, String field) {
        try {
            long value = Long.parseLong(requiredText(values, field));
            if (value <= 0) {
                throw new NumberFormatException("not positive");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw forbidden("AUTOPILOT_AUTHORIZATION_NUMERIC_FIELD_INVALID");
        }
    }

    /** 读取正整数预算字段。 */
    private int requiredInt(Map<String, Object> values, String field) {
        long value = requiredLong(values, field);
        if (value > Integer.MAX_VALUE) {
            throw forbidden("AUTOPILOT_AUTHORIZATION_NUMERIC_FIELD_INVALID");
        }
        return (int) value;
    }

    /** 读取并规范化动作编码数组，空数组保持为空而不自行扩大授权。 */
    private List<String> stringList(Object value) {
        if (!(value instanceof Iterable<?> values)) {
            throw forbidden("AUTOPILOT_AUTHORIZATION_ACTIONS_INVALID");
        }
        List<String> result = new ArrayList<>();
        for (Object item : values) {
            if (item == null || String.valueOf(item).isBlank()) {
                throw forbidden("AUTOPILOT_AUTHORIZATION_ACTIONS_INVALID");
            }
            result.add(String.valueOf(item).trim().toUpperCase(Locale.ROOT));
        }
        return List.copyOf(result);
    }

    /** 解析带时区 ISO-8601 时间，并统一到 UTC。 */
    private OffsetDateTime parseTime(String value, String field) {
        try {
            return OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC);
        } catch (RuntimeException exception) {
            throw forbidden("AUTOPILOT_" + field.toUpperCase(Locale.ROOT) + "_INVALID");
        }
    }

    /** 计算小写十六进制 SHA-256。 */
    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK does not support SHA-256", exception);
        }
    }

    /** 使用常量时间比较两个摘要或令牌样式字符串。 */
    private boolean constantEquals(String expected, String actual) {
        return actual != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    /** 判断纯 64 位或带 ``sha256:`` 前缀的指纹。 */
    private boolean sha256(String value) {
        return value != null && value.matches("(?:sha256:)?[0-9a-fA-F]{64}");
    }

    /** 判断文本是否为空。 */
    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** 创建不包含原始 payload 或身份值的稳定拒绝异常。 */
    private PlatformBusinessException forbidden(String reasonCode) {
        return new PlatformBusinessException(PlatformErrorCode.FORBIDDEN, reasonCode);
    }
}
