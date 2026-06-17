/**
 * @Author : Cui
 * @Date: 2026/06/17 00:00
 * @Description DataSmart Govern Backend - AgentToolActionResumeLocatorIndexService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionResumeFactBundleQueryRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * checkpoint/thread 到 Java 恢复事实定位符的索引服务。
 *
 * <p>它位于 fact bundle 查询入口之前，负责把“调用方显式传来的 locator”和“Java 已经学习过的 locator”
 * 合并成一次更完整的查询。这样 Python Runtime 后续可以逐步减少对 commandId/outboxId/approvalFactId 的重复传参依赖，
 * 改为先提交 checkpointId/threadId，由 Java 控制面补齐可见范围内的低敏定位符。</p>
 *
 * <p>设计约束：</p>
 * <p>1. 该服务只补齐定位符，不查询 outbox、不评估审批、不判断 receipt；事实判断仍在 bundle service 中完成；</p>
 * <p>2. 命中记录必须通过 tenant/project/actor/session/run/tool 的作用域校验，不能因为 checkpointId 撞库而跨范围补齐；</p>
 * <p>3. 响应只暴露是否命中和 evidence code，不暴露 approvalFactId/clarificationFactId 原文。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentToolActionResumeLocatorIndexService {

    private final AgentToolActionResumeLocatorIndexStore store;

    /**
     * 观察当前请求并尝试补齐缺失 locator。
     *
     * <p>调用顺序非常重要：</p>
     * <p>1. 先从当前请求提取 locator 写入 index，让 Java 可以学习 Python 5.73 以后派生出的低敏 hints；</p>
     * <p>2. 再按 checkpointId/threadId 回查 index，如果记录在当前访问范围内，则补齐缺失字段；</p>
     * <p>3. 返回新的 request record，不修改原请求，避免 Controller/日志/测试夹具看到对象被原地改变。</p>
     */
    public EnrichmentResult enrich(AgentToolActionResumeFactBundleQueryRequest request,
                                   AgentRuntimeEventProjectionQuery scopedQuery) {
        AgentToolActionResumeFactBundleQueryRequest safeRequest = request == null
                ? emptyRequest()
                : request;
        List<String> evidenceCodes = new ArrayList<>();
        AgentToolActionResumeLocatorIndexRecord incoming = recordFromRequest(safeRequest);
        if (incoming.indexable()) {
            store.upsert(incoming);
            evidenceCodes.add("LOCATOR_INDEX_OBSERVED_REQUEST_HINTS");
        }

        Optional<AgentToolActionResumeLocatorIndexRecord> candidate = findCandidate(safeRequest);
        if (candidate.isEmpty() || !visible(candidate.get(), safeRequest, scopedQuery)) {
            return new EnrichmentResult(safeRequest, false, evidenceCodes);
        }

        AgentToolActionResumeLocatorIndexRecord record = candidate.get();
        AgentToolActionResumeFactBundleQueryRequest enriched = mergeMissingFields(safeRequest, record);
        if (enriched != safeRequest) {
            evidenceCodes.add("LOCATOR_INDEX_FILLED_MISSING_FIELDS");
        }
        evidenceCodes.add("LOCATOR_INDEX_HIT");
        return new EnrichmentResult(enriched, true, evidenceCodes);
    }

    private Optional<AgentToolActionResumeLocatorIndexRecord> findCandidate(
            AgentToolActionResumeFactBundleQueryRequest request) {
        if (hasText(request.checkpointId())) {
            Optional<AgentToolActionResumeLocatorIndexRecord> byCheckpoint =
                    store.findByCheckpointId(request.checkpointId());
            if (byCheckpoint.isPresent()) {
                return byCheckpoint;
            }
        }
        if (hasText(request.threadId())) {
            return store.findByThreadId(request.threadId());
        }
        return Optional.empty();
    }

    private boolean visible(AgentToolActionResumeLocatorIndexRecord record,
                            AgentToolActionResumeFactBundleQueryRequest request,
                            AgentRuntimeEventProjectionQuery scopedQuery) {
        if (scopedQuery == null) {
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
                && matches(request.toolCode(), record.toolCode());
    }

    private AgentToolActionResumeFactBundleQueryRequest mergeMissingFields(
            AgentToolActionResumeFactBundleQueryRequest request,
            AgentToolActionResumeLocatorIndexRecord record) {
        AgentToolActionResumeFactBundleQueryRequest merged = new AgentToolActionResumeFactBundleQueryRequest(
                firstText(request.checkpointId(), record.checkpointId()),
                firstText(request.threadId(), record.threadId()),
                firstText(request.sessionId(), record.sessionId()),
                firstText(request.runId(), record.runId()),
                firstText(request.commandId(), record.commandId()),
                firstText(request.outboxId(), record.outboxId()),
                firstText(request.approvalFactId(), record.approvalFactId()),
                firstText(request.clarificationFactId(), record.clarificationFactId()),
                firstText(request.toolCode(), record.toolCode()),
                firstText(request.requestedPolicyVersion(), record.requestedPolicyVersion()),
                request.tenantId() == null ? longValue(record.tenantId()) : request.tenantId(),
                request.projectId() == null ? longValue(record.projectId()) : request.projectId(),
                firstText(request.actorId(), record.actorId()),
                request.requiredFactTypes(),
                request.includeOutboxSummary(),
                request.includeReceiptSummary()
        );
        return sameLocatorFields(request, merged) ? request : merged;
    }

    private AgentToolActionResumeLocatorIndexRecord recordFromRequest(
            AgentToolActionResumeFactBundleQueryRequest request) {
        return new AgentToolActionResumeLocatorIndexRecord(
                request.checkpointId(),
                request.threadId(),
                request.sessionId(),
                request.runId(),
                request.commandId(),
                request.outboxId(),
                request.approvalFactId(),
                request.clarificationFactId(),
                request.toolCode(),
                request.requestedPolicyVersion(),
                request.tenantId() == null ? null : String.valueOf(request.tenantId()),
                request.projectId() == null ? null : String.valueOf(request.projectId()),
                request.actorId(),
                Instant.now()
        );
    }

    private AgentToolActionResumeFactBundleQueryRequest emptyRequest() {
        return new AgentToolActionResumeFactBundleQueryRequest(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null
        );
    }

    private boolean sameLocatorFields(AgentToolActionResumeFactBundleQueryRequest left,
                                      AgentToolActionResumeFactBundleQueryRequest right) {
        return equals(left.sessionId(), right.sessionId())
                && equals(left.runId(), right.runId())
                && equals(left.commandId(), right.commandId())
                && equals(left.outboxId(), right.outboxId())
                && equals(left.approvalFactId(), right.approvalFactId())
                && equals(left.clarificationFactId(), right.clarificationFactId())
                && equals(left.toolCode(), right.toolCode())
                && equals(left.requestedPolicyVersion(), right.requestedPolicyVersion())
                && equals(left.tenantId(), right.tenantId())
                && equals(left.projectId(), right.projectId())
                && equals(left.actorId(), right.actorId());
    }

    private boolean matches(String expected, String actual) {
        return expected == null || expected.isBlank() || expected.equals(actual);
    }

    private boolean equals(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
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

    private Long longValue(String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * locator 补齐结果。
     *
     * @param request 已补齐后的请求，后续 fact bundle service 应只使用这个请求继续查询事实源。
     * @param locatorIndexHit 是否命中过可见范围内的历史 locator。
     * @param evidenceCodes 低敏证据码，只描述“是否观察/命中/补齐”，不包含任何 locator 原文。
     */
    public record EnrichmentResult(
            AgentToolActionResumeFactBundleQueryRequest request,
            boolean locatorIndexHit,
            List<String> evidenceCodes
    ) {
        public EnrichmentResult {
            evidenceCodes = evidenceCodes == null ? List.of() : List.copyOf(evidenceCodes);
        }
    }
}
