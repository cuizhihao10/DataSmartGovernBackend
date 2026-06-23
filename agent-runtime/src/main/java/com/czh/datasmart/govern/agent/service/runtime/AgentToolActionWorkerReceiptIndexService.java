/**
 * @Author : Cui
 * @Date: 2026/06/17 00:00
 * @Description DataSmart Govern Backend - AgentToolActionWorkerReceiptIndexService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * worker receipt 专用索引服务。
 *
 * <p>该服务位于通用 runtime event projection 与恢复事实包之间，负责把 receipt 事件物化成可按 commandId
 * 查询的低敏事实索引。它的职责非常克制：只解析白名单字段，不读取 message/payload，不做恢复准入决策；
 * 恢复事实是否 AVAILABLE/REJECTED 仍由 {@link AgentToolActionWorkerReceiptFactEvaluator} 完成。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentToolActionWorkerReceiptIndexService {

    private final AgentToolActionWorkerReceiptIndexStore store;

    /**
     * 从 runtime event projection 物化 receipt 索引。
     *
     * <p>该方法会被两个路径调用：</p>
     * <p>1. task-management 通过 HTTP receipt controller 回写时，agent-runtime 立即把 projection 写入索引；</p>
     * <p>2. Kafka consumer 或 fact bundle fallback 看到历史 receipt projection 时，也会幂等补写索引。</p>
     *
     * @param record 通用 runtime event projection 记录。
     * @return true 表示首次写入；false 表示事件类型不匹配、缺少 commandId 或已存在。
     */
    public boolean materialize(AgentRuntimeEventProjectionRecord record) {
        Optional<AgentToolActionWorkerReceiptIndexRecord> parsed = parse(record);
        if (parsed.isEmpty()) {
            return false;
        }
        /*
         * 这里显式调用 store.upsert，而不是把副作用藏进 Optional.filter。
         * 对恢复事实源来说，“解析失败”和“解析成功但索引已存在”是两个不同排障语义；
         * 当前返回值只需要表达是否首次写入，但代码结构要让学习者能看出物化流程。
         */
        return store.upsert(parsed.get());
    }

    /**
     * 按 commandId 和访问范围查询专用 receipt 索引。
     *
     * <p>调用方应传入已经通过 accessSupport.restrict(...) 收口后的 scopedQuery。
     * 这样索引服务不需要理解角色类型，却仍能严格执行租户、项目、actor、run、session 过滤。</p>
     */
    public List<AgentToolActionWorkerReceiptIndexRecord> queryByCommandId(String commandId,
                                                                          String toolCode,
                                                                          AgentRuntimeEventProjectionQuery scopedQuery,
                                                                          int limit) {
        if (!hasText(commandId) || scopedQuery == null) {
            return List.of();
        }
        return store.queryByCommandId(new AgentToolActionWorkerReceiptIndexQuery(
                commandId,
                toolCode,
                scopedQuery.tenantId(),
                scopedQuery.projectId(),
                scopedQuery.actorId(),
                scopedQuery.runId(),
                scopedQuery.sessionId(),
                scopedQuery.normalizedAuthorizedProjectIds(),
                limit
        ));
    }

    int size() {
        return store.size();
    }

    private Optional<AgentToolActionWorkerReceiptIndexRecord> parse(AgentRuntimeEventProjectionRecord record) {
        if (record == null || !isWorkerReceiptEvent(record.eventType())) {
            return Optional.empty();
        }
        Map<String, Object> attributes = record.attributes() == null ? Map.of() : record.attributes();
        String commandId = text(attributes.get("commandId"));
        if (!hasText(commandId)) {
            return Optional.empty();
        }
        return Optional.of(new AgentToolActionWorkerReceiptIndexRecord(
                record.identityKey(),
                commandId,
                record.tenantId(),
                record.projectId(),
                record.actorId(),
                record.runId(),
                record.sessionId(),
                text(attributes.get("toolCode")),
                text(attributes.get("taskStatus")),
                text(attributes.get("outcome")),
                bool(attributes.get("preCheckPassed")),
                bool(attributes.get("sideEffectExecuted")),
                text(attributes.get("errorCode")),
                record.replaySequence(),
                firstInstant(record.consumedAt(), record.publishedAt(), record.createdAt()),
                Instant.now()
        ));
    }

    private boolean isWorkerReceiptEvent(String eventType) {
        /*
         * 这里同时接收两类 receipt：
         * 1. controlled dry-run receipt：只证明执行前治理链路被 dry-run 调度器看见，sideEffectExecuted 必须为 false；
         * 2. command worker receipt：证明真实 worker 已完成预检或受控执行回写，sideEffectExecuted 可以为 true。
         *
         * 二者都能服务恢复事实包的同一个问题：“这条 command 是否已经被 worker 链路处理过？”
         * 因此索引层共享同一张低敏模型；但完整语义仍保留在各自 eventType 和 timeline display 中。
         */
        return AgentToolActionControlledDryRunReceiptService.EVENT_TYPE.equals(eventType)
                || AgentToolActionCommandWorkerReceiptService.EVENT_TYPE.equals(eventType);
    }

    private Instant firstInstant(Instant first, Instant second, Instant third) {
        if (first != null) {
            return first;
        }
        if (second != null) {
            return second;
        }
        return third == null ? Instant.now() : third;
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private Boolean bool(Object value) {
        if (value instanceof Boolean boolValue) {
            return boolValue;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
