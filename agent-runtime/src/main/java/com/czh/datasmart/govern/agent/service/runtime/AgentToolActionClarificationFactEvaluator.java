/**
 * @Author : Cui
 * @Date: 2026/06/17 00:00
 * @Description DataSmart Govern Backend - AgentToolActionClarificationFactEvaluator.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionResumeFactBundleQueryRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionResumeFactView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 澄清事实验真器。
 *
 * <p>该服务位于恢复事实包查询链路中，职责是把调用方传入的 {@code clarificationFactId}
 * 转换成一个“是否可被当前恢复预检采信”的低敏事实状态。</p>
 *
 * <p>为什么要单独拆出 evaluator？
 * {@code AgentToolActionResumeFactBundleService} 已经负责审批事实、outbox、worker receipt、
 * locator index 和诊断事件聚合，如果继续把澄清事实范围校验也堆进去，会再次形成胖 service。
 * 单独 evaluator 可以让“澄清事实的安全语义”集中维护：未来切 MySQL store、加 TTL 归档、
 * 接入人工确认页面或审计中心时，只需要替换仓储和登记服务，fact bundle 主流程不需要重写。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentToolActionClarificationFactEvaluator {

    private static final String FACT_CLARIFICATION = "CLARIFICATION_FACT";
    private static final String SOURCE = "CLARIFICATION_FACT_STORE";

    private final AgentToolActionClarificationFactStore store;

    /**
     * 评估澄清事实是否可用于当前恢复预检。
     *
     * <p>评估顺序刻意采用 fail-closed：
     * 先检查当前请求是否具备可信 access context，再检查 factId 是否存在，
     * 然后校验仓储记录是否对当前租户、项目、actor、run、session、command、tool、policyVersion 可见。
     * 任一步不满足，都不会把调用方自报的 factId 当作“已澄清”。</p>
     *
     * @param request 恢复事实包查询请求，提供 factId 和低敏 locator。
     * @param scopedQuery 已经经过 access support 收口后的查询边界。
     * @param accessContext gateway/permission-admin 透传的操作者上下文。
     * @param now 当前评估时间，用于判断过期。
     * @return 低敏澄清事实状态，不包含澄清原文或 factId 原文。
     */
    public AgentToolActionResumeFactView evaluate(AgentToolActionResumeFactBundleQueryRequest request,
                                                  AgentRuntimeEventProjectionQuery scopedQuery,
                                                  AgentRuntimeEventQueryAccessContext accessContext,
                                                  Instant now) {
        if (accessContext == null || !accessContext.hasIdentity()) {
            return fact("MISSING", false, false, false,
                    List.of(), List.of("ACCESS_CONTEXT_REQUIRED_FOR_CLARIFICATION_FACT_EVALUATION"), now);
        }
        if (request == null || !hasText(request.clarificationFactId())) {
            return fact("MISSING", false, false, false,
                    List.of(), List.of("CLARIFICATION_FACT_ID_REQUIRED"), now);
        }
        return store.findByFactId(request.clarificationFactId())
                .map(record -> evaluateRecord(record, request, scopedQuery, now))
                .orElseGet(() -> fact("MISSING", false, false, false,
                        List.of(), List.of("CLARIFICATION_FACT_NOT_FOUND_OR_NOT_VISIBLE"), now));
    }

    private AgentToolActionResumeFactView evaluateRecord(AgentToolActionClarificationFactRecord record,
                                                         AgentToolActionResumeFactBundleQueryRequest request,
                                                         AgentRuntimeEventProjectionQuery scopedQuery,
                                                         Instant now) {
        if (!visible(record, request, scopedQuery)) {
            /*
             * 跨租户、跨项目、跨 actor 或跨 run/session 的事实，统一伪装成“未找到或不可见”。
             * 这样外部调用方不能通过不同 factId 的返回状态探测其他项目是否存在用户澄清。
             */
            return fact("MISSING", false, false, false,
                    List.of(), List.of("CLARIFICATION_FACT_NOT_FOUND_OR_NOT_VISIBLE"), now);
        }
        if (record.expiredAt(now)) {
            return fact("REJECTED", false, true, false,
                    evidence(record, "CLARIFICATION_FACT_FOUND", "CLARIFICATION_FACT_SCOPE_MATCHED"),
                    issues(record, "CLARIFICATION_FACT_EXPIRED"), now);
        }
        if (!record.statusAvailable()) {
            return fact("REJECTED", false, true, false,
                    evidence(record, "CLARIFICATION_FACT_FOUND", "CLARIFICATION_FACT_SCOPE_MATCHED"),
                    issues(record, "CLARIFICATION_FACT_" + record.status()), now);
        }
        if (policyMismatch(record, request)) {
            return fact("REJECTED", false, true, false,
                    evidence(record, "CLARIFICATION_FACT_FOUND", "CLARIFICATION_FACT_SCOPE_MATCHED"),
                    issues(record, "CLARIFICATION_FACT_POLICY_VERSION_MISMATCH"), now);
        }
        return fact("AVAILABLE", true, false, false,
                evidence(
                        record,
                        "CLARIFICATION_FACT_FOUND",
                        "CLARIFICATION_FACT_SCOPE_MATCHED",
                        "CLARIFICATION_FACT_CONTENT_NOT_EXPOSED"
                ),
                record.issueCodes(),
                now);
    }

    private boolean visible(AgentToolActionClarificationFactRecord record,
                            AgentToolActionResumeFactBundleQueryRequest request,
                            AgentRuntimeEventProjectionQuery scopedQuery) {
        if (record == null || scopedQuery == null) {
            return false;
        }
        List<String> authorizedProjectIds = scopedQuery.normalizedAuthorizedProjectIds();
        if (authorizedProjectIds != null && hasText(record.projectId())
                && !authorizedProjectIds.contains(record.projectId())) {
            return false;
        }
        return matches(scopedQuery.tenantId(), record.tenantId())
                && matches(scopedQuery.projectId(), record.projectId())
                && matches(scopedQuery.actorId(), record.actorId())
                && matches(scopedQuery.runId(), firstText(record.runId(), request.runId()))
                && matches(scopedQuery.sessionId(), firstText(record.sessionId(), request.sessionId()))
                && matches(request.commandId(), record.commandId())
                && matches(request.toolCode(), record.toolCode());
    }

    private boolean policyMismatch(AgentToolActionClarificationFactRecord record,
                                   AgentToolActionResumeFactBundleQueryRequest request) {
        return hasText(request.requestedPolicyVersion())
                && hasText(record.requestedPolicyVersion())
                && !request.requestedPolicyVersion().trim().equals(record.requestedPolicyVersion());
    }

    private List<String> evidence(AgentToolActionClarificationFactRecord record, String... defaults) {
        return mergeCodes(record.evidenceCodes(), defaults);
    }

    private List<String> issues(AgentToolActionClarificationFactRecord record, String... defaults) {
        return mergeCodes(record.issueCodes(), defaults);
    }

    private List<String> mergeCodes(List<String> existing, String... defaults) {
        List<String> result = new ArrayList<>();
        if (existing != null) {
            existing.stream().filter(this::hasText).forEach(result::add);
        }
        if (defaults != null) {
            for (String value : defaults) {
                if (hasText(value) && !result.contains(value)) {
                    result.add(value);
                }
            }
        }
        return List.copyOf(result);
    }

    private AgentToolActionResumeFactView fact(String status,
                                               boolean available,
                                               boolean rejected,
                                               boolean retryable,
                                               List<String> evidenceCodes,
                                               List<String> issueCodes,
                                               Instant checkedAt) {
        return new AgentToolActionResumeFactView(
                FACT_CLARIFICATION,
                SOURCE,
                status,
                available,
                rejected,
                retryable,
                evidenceCodes == null ? List.of() : List.copyOf(evidenceCodes),
                issueCodes == null ? List.of() : List.copyOf(issueCodes),
                AgentToolActionResumeFactBundleService.PAYLOAD_POLICY,
                checkedAt
        );
    }

    private boolean matches(String expected, String actual) {
        return expected == null || expected.isBlank() || expected.equals(actual);
    }

    private String firstText(String first, String fallback) {
        return hasText(first) ? first.trim() : text(fallback);
    }

    private String text(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
