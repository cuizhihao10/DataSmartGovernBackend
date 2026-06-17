/**
 * @Author : Cui
 * @Date: 2026/06/17 00:00
 * @Description DataSmart Govern Backend - AgentToolActionClarificationFactRegistrationService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.config.AgentToolActionResumeFactBundleProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionClarificationFactUpsertRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionClarificationFactView;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 澄清事实登记服务。
 *
 * <p>Controller 只负责 HTTP Header 和 body 接入，本服务负责把“用户完成澄清”转成
 * Java 控制面可回查的低敏事实记录。它会同时处理参数校验、可信 Header 收口、项目范围校验、
 * 默认 TTL、状态归一化和仓储写入。</p>
 *
 * <p>业务边界：
 * 1. 本服务不保存用户补充的自然语言内容；
 * 2. 本服务不代表工具执行获批，只代表“澄清事实存在且可被后续恢复预检查询”；
 * 3. 真实 resume 仍必须继续通过 approval fact、command outbox、worker receipt、配额和审计。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentToolActionClarificationFactRegistrationService {

    private static final String PAYLOAD_POLICY =
            "LOW_SENSITIVE_CLARIFICATION_FACT_METADATA_ONLY_NO_USER_CONTENT";

    private final AgentToolActionClarificationFactStore store;
    private final AgentToolActionResumeFactBundleProperties properties;

    /**
     * 登记或更新澄清事实。
     *
     * <p>该方法采用幂等 upsert 语义：同一个 clarificationFactId 可以重复提交，
     * 后一次提交会更新状态、过期时间和低敏枚举码。这适配前端重试、网关超时重放和用户撤销澄清等真实场景。</p>
     *
     * @param request 澄清事实登记请求，不能包含用户澄清原文。
     * @param accessContext gateway/permission-admin 可信 Header 解析出的访问上下文。
     * @return 登记后的低敏事实视图。
     */
    public AgentToolActionClarificationFactView upsert(AgentToolActionClarificationFactUpsertRequest request,
                                                       AgentRuntimeEventQueryAccessContext accessContext) {
        if (request == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST, "澄清事实登记请求不能为空");
        }
        if (accessContext == null || !accessContext.hasIdentity()) {
            throw new PlatformBusinessException(PlatformErrorCode.UNAUTHORIZED,
                    "澄清事实登记必须携带可信租户和操作者上下文");
        }
        validateRequiredText(request.clarificationFactId(), "clarificationFactId");
        if (!hasText(request.runId()) && !hasText(request.sessionId())) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "澄清事实必须至少绑定 runId 或 sessionId，避免事实脱离 Agent 暂停点边界");
        }
        validateBodyScope(request, accessContext);

        Instant now = Instant.now();
        AgentToolActionClarificationFactRecord record = new AgentToolActionClarificationFactRecord(
                request.clarificationFactId(),
                request.sessionId(),
                request.runId(),
                request.commandId(),
                request.toolCode(),
                request.requestedPolicyVersion(),
                String.valueOf(accessContext.tenantId()),
                resolveProjectId(request, accessContext),
                String.valueOf(accessContext.actorId()),
                normalizedStatus(request.status()),
                evidenceCodes(request),
                lowSensitiveCodes(request.issueCodes()),
                resolveExpiresAt(request, now),
                now,
                now
        );
        store.upsert(record);
        return AgentToolActionClarificationFactView.from(record, now, PAYLOAD_POLICY);
    }

    private void validateBodyScope(AgentToolActionClarificationFactUpsertRequest request,
                                   AgentRuntimeEventQueryAccessContext accessContext) {
        if (request.tenantId() != null && !request.tenantId().equals(accessContext.tenantId())) {
            throw new PlatformBusinessException(PlatformErrorCode.TENANT_SCOPE_DENIED,
                    "澄清事实登记的 tenantId 与可信 Header 不一致");
        }
        if (hasText(request.actorId()) && !request.actorId().trim().equals(String.valueOf(accessContext.actorId()))) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "澄清事实不能代表其他 actor 登记");
        }
        Long projectId = request.projectId();
        if (accessContext.explicitProjectScope()) {
            List<Long> authorizedProjectIds = authorizedProjectIds(accessContext);
            if (projectId == null && authorizedProjectIds.size() != 1) {
                throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                        "PROJECT 数据范围下登记澄清事实必须提供明确 projectId");
            }
            if (projectId != null && !authorizedProjectIds.contains(projectId)) {
                throw new PlatformBusinessException(PlatformErrorCode.TENANT_SCOPE_DENIED,
                        "澄清事实 projectId 不在当前授权项目范围内");
            }
        }
    }

    private String resolveProjectId(AgentToolActionClarificationFactUpsertRequest request,
                                    AgentRuntimeEventQueryAccessContext accessContext) {
        if (request.projectId() != null) {
            return String.valueOf(request.projectId());
        }
        List<Long> authorizedProjectIds = authorizedProjectIds(accessContext);
        if (accessContext.explicitProjectScope() && authorizedProjectIds.size() == 1) {
            return String.valueOf(authorizedProjectIds.getFirst());
        }
        throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                "澄清事实必须绑定 projectId，后续恢复预检才能执行项目范围校验");
    }

    private Instant resolveExpiresAt(AgentToolActionClarificationFactUpsertRequest request, Instant now) {
        if (request.expiresAt() != null) {
            if (!request.expiresAt().isAfter(now)) {
                throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                        "澄清事实 expiresAt 必须晚于当前时间");
            }
            return request.expiresAt();
        }
        long ttlSeconds = normalizedDefaultTtlSeconds();
        return now.plusSeconds(ttlSeconds);
    }

    private long normalizedDefaultTtlSeconds() {
        Long configured = properties == null ? null : properties.getClarificationFactDefaultTtlSeconds();
        if (configured == null || configured <= 0) {
            return 3600L;
        }
        return Math.min(configured, 7 * 24 * 3600L);
    }

    private List<String> evidenceCodes(AgentToolActionClarificationFactUpsertRequest request) {
        List<String> result = new ArrayList<>();
        result.add("USER_CLARIFICATION_CAPTURED");
        result.add("CLARIFICATION_FACT_CONTENT_NOT_STORED");
        lowSensitiveCodes(request.evidenceCodes()).stream()
                .filter(code -> !result.contains(code))
                .forEach(result::add);
        return List.copyOf(result);
    }

    private List<String> lowSensitiveCodes(List<String> codes) {
        if (codes == null) {
            return List.of();
        }
        return codes.stream()
                .filter(this::hasText)
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .filter(value -> value.length() <= 80)
                .filter(value -> value.matches("[A-Z0-9_\\-]+"))
                .distinct()
                .toList();
    }

    private String normalizedStatus(String status) {
        if (!hasText(status)) {
            return AgentToolActionClarificationFactRecord.STATUS_AVAILABLE;
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (AgentToolActionClarificationFactRecord.STATUS_AVAILABLE.equals(normalized)
                || AgentToolActionClarificationFactRecord.STATUS_REVOKED.equals(normalized)
                || AgentToolActionClarificationFactRecord.STATUS_REJECTED.equals(normalized)) {
            return normalized;
        }
        throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                "不支持的澄清事实状态: " + status);
    }

    private void validateRequiredText(String value, String fieldName) {
        if (!hasText(value)) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "澄清事实登记缺少必填字段: " + fieldName);
        }
    }

    private List<Long> authorizedProjectIds(AgentRuntimeEventQueryAccessContext accessContext) {
        return accessContext.authorizedProjectIds() == null ? List.of() : accessContext.authorizedProjectIds();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
