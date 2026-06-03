/**
 * @Author : Cui
 * @Date: 2026/06/04 00:00
 * @Description DataSmart Govern Backend - AgentAsyncTaskCommandPreCheckRuntimeEventPublisher.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.event.command;

import com.czh.datasmart.govern.agent.service.execution.AgentAsyncTaskCommandPreCheckVerdict;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventProjectionRecord;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventProjectionStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 异步命令 pre-check runtime event 发布器。
 *
 * <p>dispatcher 的核心职责是 outbox 状态推进和命令投递；runtime event 的职责是让前端时间线、审计 replay
 * 和运维排障看见“为什么命令没有继续投递”。如果把事件组装逻辑直接塞进 dispatcher，dispatcher 会同时承担
 * 状态机、投递、pre-check、事件摘要四类职责，后续很容易重新膨胀成难维护的大文件。</p>
 *
 * <p>本发布器只写低敏摘要：
 * - 写 commandId、auditId、toolCode、targetService、decision、issueCodes 等稳定事实；
 * - 不写 payloadJson、工具参数、SQL、prompt、完整 reason 列表或下游响应；
 * - message 只保留面向运营的概括说明。
 * 这样事件既能进入 replay/display，又不会成为敏感数据扩散面。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentAsyncTaskCommandPreCheckRuntimeEventPublisher {

    public static final String SCHEMA_VERSION = "datasmart.agent-runtime.async-command-pre-check.v1";
    public static final String SOURCE = "JAVA_AGENT_RUNTIME";
    public static final String EVENT_TYPE = "agent.tool_execution.state_changed";
    public static final String STAGE_BLOCKED = "tool_pre_check_blocked";
    public static final String STAGE_DEFERRED = "tool_pre_check_deferred";

    private final AgentRuntimeEventProjectionStore projectionStore;

    /**
     * 发布 pre-check 阻断或暂缓事件。
     *
     * <p>该方法保持“尽力而为”：事件写入失败不应反向改变 outbox 状态。原因是 dispatcher 已经按照 pre-check
     * verdict 把 command 标记为 BLOCKED 或 FAILED；如果事件投影失败再回滚 outbox，反而会让状态机不稳定。
     * 生产阶段若要求强审计，应把 runtime event 升级为事务 outbox，而不是在这里抛异常。</p>
     */
    public void publish(AgentAsyncTaskCommandOutboxRecord record, AgentAsyncTaskCommandPreCheckVerdict verdict) {
        if (record == null || verdict == null || Boolean.TRUE.equals(verdict.allowed())) {
            return;
        }
        try {
            projectionStore.append(toProjectionRecord(record, verdict));
        } catch (RuntimeException exception) {
            log.warn("Agent 异步命令 pre-check runtime event 写入失败，commandId={}, decision={}, error={}",
                    record.commandId(), verdict.decision(), exception.getMessage());
        }
    }

    private AgentRuntimeEventProjectionRecord toProjectionRecord(AgentAsyncTaskCommandOutboxRecord record,
                                                                 AgentAsyncTaskCommandPreCheckVerdict verdict) {
        Instant now = Instant.now();
        String stage = "DEFERRED".equals(verdict.decision()) ? STAGE_DEFERRED : STAGE_BLOCKED;
        return new AgentRuntimeEventProjectionRecord(
                identityKey(record, verdict),
                SCHEMA_VERSION,
                SOURCE,
                EVENT_TYPE,
                stage,
                message(record, verdict),
                "DEFERRED".equals(verdict.decision()) ? "audit" : "error",
                record.tenantId() == null ? null : String.valueOf(record.tenantId()),
                record.projectId() == null ? null : String.valueOf(record.projectId()),
                record.actorId(),
                record.traceId(),
                record.runId(),
                record.sessionId(),
                null,
                now,
                now,
                now,
                attributes(record, verdict)
        );
    }

    private String identityKey(AgentAsyncTaskCommandOutboxRecord record, AgentAsyncTaskCommandPreCheckVerdict verdict) {
        return "async-command-pre-check:" + record.commandId() + ":" + verdict.decision() + ":" + record.attemptCount();
    }

    private String message(AgentAsyncTaskCommandOutboxRecord record, AgentAsyncTaskCommandPreCheckVerdict verdict) {
        if ("DEFERRED".equals(verdict.decision())) {
            return "Agent 异步命令执行前复核暂缓，commandId=" + record.commandId()
                    + "，等待重试，建议按退避策略处理，issueCodes=" + verdict.issueCodes() + "。";
        }
        return "Agent 异步命令执行前复核阻断，commandId=" + record.commandId()
                + "，worker 已阻止副作用，issueCodes=" + verdict.issueCodes() + "。";
    }

    private Map<String, Object> attributes(AgentAsyncTaskCommandOutboxRecord record,
                                           AgentAsyncTaskCommandPreCheckVerdict verdict) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("errorCode", "DEFERRED".equals(verdict.decision())
                ? "AGENT_ASYNC_TOOL_PRECHECK_DEFERRED"
                : "AGENT_ASYNC_TOOL_PRECHECK_BLOCKED");
        attributes.put("preCheckDecision", verdict.decision());
        attributes.put("issueCodes", safeList(verdict.issueCodes()));
        attributes.put("commandId", record.commandId());
        attributes.put("outboxId", record.outboxId());
        attributes.put("auditId", record.auditId());
        attributes.put("toolCode", record.toolCode());
        attributes.put("targetService", record.targetService());
        attributes.put("currentState", "DEFERRED".equals(verdict.decision()) ? "FAILED" : "BLOCKED");
        attributes.put("confirmationId", verdict.confirmationId());
        attributes.put("confirmationStatus", verdict.confirmationStatus());
        attributes.put("confirmationExpiresAt", verdict.confirmationExpiresAt());
        attributes.put("policyDecision", verdict.policyDecision());
        attributes.put("sandboxAllowed", verdict.sandboxAllowed());
        attributes.put("runtimeProtectionAllowed", verdict.runtimeProtectionAllowed());
        attributes.put("sideEffectPrevented", true);
        attributes.put("eventPayloadPolicy", "SUMMARY_ONLY_NO_PAYLOAD_JSON_NO_TOOL_ARGUMENTS");
        /*
         * 这里不使用 Map.copyOf：
         * 1. pre-check verdict 中的 confirmationStatus、policyDecision、confirmationExpiresAt 等字段在真实异常场景
         *    可能为空，例如 confirmation 查询失败、permission-admin 尚未返回策略结论；
         * 2. Map.copyOf 会拒绝 null value，导致“本应只是事件投影失败”的情况变成运行时异常；
         * 3. runtime event 的目标是帮助诊断，所以保留 null 字段反而能表达“该证据链缺失”。
         */
        return Collections.unmodifiableMap(attributes);
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }
}
