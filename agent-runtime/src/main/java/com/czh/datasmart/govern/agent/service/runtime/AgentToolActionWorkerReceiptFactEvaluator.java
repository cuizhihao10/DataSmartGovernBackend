/**
 * @Author : Cui
 * @Date: 2026/06/17 00:00
 * @Description DataSmart Govern Backend - AgentToolActionWorkerReceiptFactEvaluator.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionResumeFactBundleQueryRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionResumeFactReceiptSummaryView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionResumeFactView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * worker receipt 恢复事实验真器。
 *
 * <p>该类把原先散落在 `AgentToolActionResumeFactBundleService` 里的 receipt 查询、fallback、最新状态判断和摘要拼装
 * 全部收口到一个专门组件。这样主服务保持“事实包编排器”职责，receipt 自身则按照可替换索引模型演进。</p>
 *
 * <p>当前查询策略是：</p>
 * <p>1. 先查 `AgentToolActionWorkerReceiptIndexStore`，命中时走专用索引；</p>
 * <p>2. 如果索引没命中，再回退查询通用 runtime event projection，并把回退命中的记录补写进索引；</p>
 * <p>3. 响应只暴露 receiptCount/outcome/taskStatus/preCheckPassed/errorCode，不暴露 message、payload、工具参数。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentToolActionWorkerReceiptFactEvaluator {

    public static final String FACT_WORKER_RECEIPT = "WORKER_RECEIPT_PROJECTION";
    public static final String PAYLOAD_POLICY =
            "FACT_TYPE_AND_CONTROL_STATUS_ONLY_NO_RECEIPT_MESSAGE_NO_PAYLOAD_BODY";

    private static final String SOURCE_INDEX = "WORKER_RECEIPT_LOW_SENSITIVE_INDEX";
    private static final String SOURCE_FALLBACK = "RUNTIME_EVENT_PROJECTION_FALLBACK";

    private final AgentToolActionWorkerReceiptIndexService receiptIndexService;
    private final AgentRuntimeEventProjectionStore projectionStore;

    /**
     * 评估某个 commandId 是否具备 worker/dry-run receipt 事实。
     *
     * @param request 恢复事实包查询请求，至少需要 commandId 才能定位 receipt。
     * @param scopedQuery 已经过访问上下文收口的 projection 查询边界。
     * @param now 本次事实验真时间，统一由主服务传入，方便响应中各事实 checkedAt 保持一致。
     * @param queryLimit 单次索引/投影查询上限。
     * @return worker receipt fact 和可选低敏摘要。
     */
    public AgentToolActionWorkerReceiptFactEvaluation evaluate(
            AgentToolActionResumeFactBundleQueryRequest request,
            AgentRuntimeEventProjectionQuery scopedQuery,
            Instant now,
            int queryLimit) {
        if (!hasText(request.commandId())) {
            return new AgentToolActionWorkerReceiptFactEvaluation(
                    fact(SOURCE_INDEX, "MISSING", false, false, false,
                            List.of(), List.of("WORKER_RECEIPT_COMMAND_ID_REQUIRED"), now),
                    null
            );
        }

        List<AgentToolActionWorkerReceiptIndexRecord> indexedReceipts = receiptIndexService.queryByCommandId(
                request.commandId(),
                request.toolCode(),
                scopedQuery,
                queryLimit
        );
        if (!indexedReceipts.isEmpty()) {
            return evaluationFromRecords(indexedReceipts, SOURCE_INDEX, now);
        }

        List<AgentToolActionWorkerReceiptIndexRecord> fallbackReceipts =
                queryProjectionFallbackAndMaterialize(request, scopedQuery, queryLimit);
        if (fallbackReceipts.isEmpty()) {
            return new AgentToolActionWorkerReceiptFactEvaluation(
                    fact(SOURCE_INDEX, "MISSING", false, false, true,
                            List.of(), List.of("WORKER_RECEIPT_NOT_FOUND"), now),
                    new AgentToolActionResumeFactReceiptSummaryView(
                            0, false, null, null, null, null, null, null, null, PAYLOAD_POLICY
                    )
            );
        }
        return evaluationFromRecords(fallbackReceipts, SOURCE_FALLBACK, now);
    }

    private List<AgentToolActionWorkerReceiptIndexRecord> queryProjectionFallbackAndMaterialize(
            AgentToolActionResumeFactBundleQueryRequest request,
            AgentRuntimeEventProjectionQuery scopedQuery,
            int queryLimit) {
        AgentRuntimeEventProjectionQuery receiptQuery = new AgentRuntimeEventProjectionQuery(
                scopedQuery.tenantId(),
                scopedQuery.projectId(),
                scopedQuery.actorId(),
                null,
                scopedQuery.runId(),
                scopedQuery.sessionId(),
                AgentToolActionControlledDryRunReceiptService.EVENT_TYPE,
                null,
                queryLimit,
                scopedQuery.afterSequence(),
                scopedQuery.authorizedProjectIds()
        );
        List<AgentRuntimeEventProjectionRecord> records = projectionStore.query(receiptQuery).stream()
                .filter(record -> request.commandId().equals(text(record.attributes().get("commandId"))))
                .toList();
        records.forEach(receiptIndexService::materialize);
        return receiptIndexService.queryByCommandId(request.commandId(), request.toolCode(), scopedQuery, queryLimit);
    }

    private AgentToolActionWorkerReceiptFactEvaluation evaluationFromRecords(
            List<AgentToolActionWorkerReceiptIndexRecord> receipts,
            String source,
            Instant now) {
        AgentToolActionWorkerReceiptIndexRecord latest = receipts.getLast();
        boolean rejected = latest.rejectedBeforeExecution();
        List<String> issueCodes = rejected
                ? List.of(firstText(latest.errorCode(), "WORKER_RECEIPT_PRECHECK_FAILED"))
                : List.of();
        return new AgentToolActionWorkerReceiptFactEvaluation(
                fact(
                        source,
                        rejected ? "REJECTED" : "AVAILABLE",
                        !rejected,
                        rejected,
                        !Boolean.TRUE.equals(latest.preCheckPassed()) && !rejected,
                        List.of("WORKER_RECEIPT_INDEX_FOUND", "WORKER_RECEIPT_MESSAGE_NOT_EXPOSED"),
                        issueCodes,
                        now
                ),
                new AgentToolActionResumeFactReceiptSummaryView(
                        receipts.size(),
                        true,
                        latest.replaySequence(),
                        latest.outcome(),
                        latest.taskStatus(),
                        latest.preCheckPassed(),
                        latest.sideEffectExecuted(),
                        latest.errorCode(),
                        latest.consumedAt(),
                        PAYLOAD_POLICY
                )
        );
    }

    private AgentToolActionResumeFactView fact(String source,
                                               String status,
                                               boolean available,
                                               boolean rejected,
                                               boolean retryable,
                                               List<String> evidenceCodes,
                                               List<String> issueCodes,
                                               Instant checkedAt) {
        return new AgentToolActionResumeFactView(
                FACT_WORKER_RECEIPT,
                source,
                status,
                available,
                rejected,
                retryable,
                evidenceCodes,
                issueCodes,
                PAYLOAD_POLICY,
                checkedAt
        );
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private String firstText(String first, String fallback) {
        return hasText(first) ? first.trim() : text(fallback);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
