/**
 * @Author : Cui
 * @Date: 2026/06/24 23:46
 * @Description DataSmart Govern Backend - AgentAsyncTaskCommandOutboxOperationService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.execution;

import com.czh.datasmart.govern.agent.controller.dto.AgentAsyncTaskCommandOutboxOperationRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentAsyncTaskCommandOutboxOperationResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentAsyncTaskCommandOutboxRecordView;
import com.czh.datasmart.govern.agent.event.command.AgentAsyncTaskCommandOutboxOperationStore;
import com.czh.datasmart.govern.agent.event.command.AgentAsyncTaskCommandOutboxRecord;
import com.czh.datasmart.govern.agent.event.command.AgentAsyncTaskCommandOutboxStatus;
import com.czh.datasmart.govern.agent.event.command.AgentAsyncTaskCommandOutboxStore;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/**
 * Agent 异步命令 outbox 人工补偿服务。
 *
 * <p>dispatcher 负责自动投递、失败退避和 stale PUBLISHING 恢复；本服务负责“自动路径无法安全继续时，管理员如何接手”。
 * 它是 command durable action 闭环中的运营出口：失败或阻断命令可以进入 DEAD_LETTER，可以修复后重新入队，
 * 也可以在确认无价值后人工忽略。</p>
 *
 * <p>该服务不读取 payloadJson、不调用下游 task-management、不执行命令，也不绕过 worker lease/receipt。
 * 它只改变 outbox 的低敏状态，让真实执行仍然必须经过 dispatcher、worker pre-check、lease、receipt 和 artifact gate。</p>
 */
@Service
public class AgentAsyncTaskCommandOutboxOperationService {

    /**
     * 人工重排最大延迟。
     *
     * <p>补偿台允许延迟 requeue，是为了等下游 ACL、topic、服务部署或限流窗口恢复。但延迟不能无限大，
     * 否则一条死信可能被误排到很久以后，运维看板又无法解释为什么没有被处理。</p>
     */
    private static final int MAX_RETRY_DELAY_SECONDS = 24 * 60 * 60;

    private final AgentAsyncTaskCommandOutboxStore outboxStore;
    private final AgentAsyncTaskCommandOutboxOperationStore operationStore;
    private final Clock clock;

    public AgentAsyncTaskCommandOutboxOperationService(AgentAsyncTaskCommandOutboxStore outboxStore,
                                                       AgentAsyncTaskCommandOutboxOperationStore operationStore) {
        this(outboxStore, operationStore, Clock.systemUTC());
    }

    AgentAsyncTaskCommandOutboxOperationService(AgentAsyncTaskCommandOutboxStore outboxStore,
                                                AgentAsyncTaskCommandOutboxOperationStore operationStore,
                                                Clock clock) {
        this.outboxStore = outboxStore;
        this.operationStore = operationStore;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /**
     * 将失败、阻断或死信命令重新放回 PENDING。
     *
     * <p>requeue 代表“已修复导致失败的问题，可以让 dispatcher 再次投递”。它不清空 attemptCount，
     * 因为尝试次数是后续判断 command 是否反复失败的重要运营证据。</p>
     */
    public AgentAsyncTaskCommandOutboxOperationResponse requeue(String outboxId,
                                                                AgentAsyncTaskCommandOutboxOperationRequest request,
                                                                String headerActorId) {
        AgentAsyncTaskCommandOutboxRecord before = findExisting(outboxId);
        if (!canRequeue(before.status())) {
            throw conflict(outboxId, before.status(), "REQUEUE");
        }
        Instant now = clock.instant();
        Instant nextRetryAt = nextRetryAt(request, now);
        String operatorId = resolveOperator(request, headerActorId);
        String reason = buildOperationReason("REQUEUE", request, operatorId, now);
        AgentAsyncTaskCommandOutboxRecord updated =
                operationStore.markRequeued(before.outboxId(), reason, now, nextRetryAt)
                        .orElseThrow(() -> conflict(outboxId, before.status(), "REQUEUE"));
        return response("REQUEUE", before, updated, operatorId, reason, now, nextRetryAt);
    }

    /**
     * 将失败或阻断命令转入 DEAD_LETTER。
     *
     * <p>dead-letter 是自动恢复的刹车：它明确告诉 dispatcher 不要继续领取这条 command，避免坏配置、
     * 缺权限或不可恢复契约错误造成热循环。后续管理员可以在排障后执行 requeue 或 ignore。</p>
     */
    public AgentAsyncTaskCommandOutboxOperationResponse deadLetter(String outboxId,
                                                                   AgentAsyncTaskCommandOutboxOperationRequest request,
                                                                   String headerActorId) {
        return operate(outboxId, request, headerActorId, "DEAD_LETTER",
                this::canDeadLetter,
                operationStore::markDeadLetter);
    }

    /**
     * 人工忽略失败、阻断或死信命令。
     */
    public AgentAsyncTaskCommandOutboxOperationResponse ignore(String outboxId,
                                                               AgentAsyncTaskCommandOutboxOperationRequest request,
                                                               String headerActorId) {
        return operate(outboxId, request, headerActorId, "IGNORE",
                this::canIgnore,
                operationStore::markIgnored);
    }

    /**
     * 为非成功命令追加人工备注。
     */
    public AgentAsyncTaskCommandOutboxOperationResponse appendNote(String outboxId,
                                                                   AgentAsyncTaskCommandOutboxOperationRequest request,
                                                                   String headerActorId) {
        AgentAsyncTaskCommandOutboxRecord before = findExisting(outboxId);
        if (before.status() == AgentAsyncTaskCommandOutboxStatus.PUBLISHED) {
            throw conflict(outboxId, before.status(), "NOTE");
        }
        Instant now = clock.instant();
        String operatorId = resolveOperator(request, headerActorId);
        String reason = buildOperationReason("NOTE", request, operatorId, now);
        AgentAsyncTaskCommandOutboxRecord updated =
                operationStore.appendOperationNote(before.outboxId(), reason, now)
                        .orElseThrow(() -> conflict(outboxId, before.status(), "NOTE"));
        return response("NOTE", before, updated, operatorId, reason, now, updated.nextRetryAt());
    }

    private AgentAsyncTaskCommandOutboxOperationResponse operate(String outboxId,
                                                                 AgentAsyncTaskCommandOutboxOperationRequest request,
                                                                 String headerActorId,
                                                                 String action,
                                                                 StatusPredicate predicate,
                                                                 CommandOutboxMutation mutation) {
        AgentAsyncTaskCommandOutboxRecord before = findExisting(outboxId);
        if (!predicate.test(before.status())) {
            throw conflict(outboxId, before.status(), action);
        }
        Instant now = clock.instant();
        String operatorId = resolveOperator(request, headerActorId);
        String reason = buildOperationReason(action, request, operatorId, now);
        AgentAsyncTaskCommandOutboxRecord updated = mutation.apply(before.outboxId(), reason, now)
                .orElseThrow(() -> conflict(outboxId, before.status(), action));
        return response(action, before, updated, operatorId, reason, now, updated.nextRetryAt());
    }

    private AgentAsyncTaskCommandOutboxRecord findExisting(String outboxId) {
        String normalizedOutboxId = requiredSafeId(outboxId, "outboxId");
        return outboxStore.findByOutboxId(normalizedOutboxId)
                .orElseThrow(() -> new PlatformBusinessException(
                        PlatformErrorCode.NOT_FOUND,
                        "Agent 异步命令 outbox 记录不存在，outboxId=" + normalizedOutboxId
                ));
    }

    private AgentAsyncTaskCommandOutboxOperationResponse response(String action,
                                                                  AgentAsyncTaskCommandOutboxRecord before,
                                                                  AgentAsyncTaskCommandOutboxRecord updated,
                                                                  String operatorId,
                                                                  String reason,
                                                                  Instant now,
                                                                  Instant nextRetryAt) {
        return new AgentAsyncTaskCommandOutboxOperationResponse(
                action,
                updated.outboxId(),
                before.status().name(),
                updated.status().name(),
                operatorId,
                reason,
                now,
                nextRetryAt,
                AgentAsyncTaskCommandOutboxRecordView.from(updated)
        );
    }

    private Instant nextRetryAt(AgentAsyncTaskCommandOutboxOperationRequest request, Instant now) {
        int delaySeconds = request == null || request.retryDelaySeconds() == null
                ? 0
                : Math.max(0, Math.min(request.retryDelaySeconds(), MAX_RETRY_DELAY_SECONDS));
        return delaySeconds == 0 ? null : now.plusSeconds(delaySeconds);
    }

    private String buildOperationReason(String action,
                                        AgentAsyncTaskCommandOutboxOperationRequest request,
                                        String operatorId,
                                        Instant now) {
        String reason = Optional.ofNullable(request)
                .map(AgentAsyncTaskCommandOutboxOperationRequest::reason)
                .filter(this::hasText)
                .map(String::trim)
                .orElseThrow(() -> new PlatformBusinessException(
                        PlatformErrorCode.BAD_REQUEST,
                        "Agent 异步命令 outbox 人工补偿必须填写 reason"
                ));
        if (looksSensitive(reason)) {
            throw new PlatformBusinessException(
                    PlatformErrorCode.BAD_REQUEST,
                    "reason 只能填写低敏排障说明，不能包含命令、路径、URL、输出、SQL、prompt 或凭据"
            );
        }
        return "人工补偿动作=" + action
                + ", operatorId=" + operatorId
                + ", operatedAt=" + now
                + ", reason=" + truncate(reason, 260);
    }

    private String resolveOperator(AgentAsyncTaskCommandOutboxOperationRequest request, String headerActorId) {
        if (request != null && hasText(request.operatorId())) {
            return requiredSafeId(request.operatorId(), "operatorId");
        }
        if (hasText(headerActorId)) {
            return requiredSafeId(headerActorId, "operatorId");
        }
        return "unknown-operator";
    }

    private String requiredSafeId(String value, String fieldName) {
        if (!hasText(value)) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST, fieldName + " 不能为空");
        }
        String text = value.trim();
        if (text.length() > 260 || looksSensitive(text)) {
            throw new PlatformBusinessException(
                    PlatformErrorCode.BAD_REQUEST,
                    fieldName + " 只能使用低敏标识符，不能携带命令、路径、URL、输出、SQL、prompt 或凭据"
            );
        }
        return text;
    }

    private boolean canRequeue(AgentAsyncTaskCommandOutboxStatus status) {
        return status == AgentAsyncTaskCommandOutboxStatus.FAILED
                || status == AgentAsyncTaskCommandOutboxStatus.BLOCKED
                || status == AgentAsyncTaskCommandOutboxStatus.DEAD_LETTER;
    }

    private boolean canDeadLetter(AgentAsyncTaskCommandOutboxStatus status) {
        return status == AgentAsyncTaskCommandOutboxStatus.FAILED
                || status == AgentAsyncTaskCommandOutboxStatus.BLOCKED;
    }

    private boolean canIgnore(AgentAsyncTaskCommandOutboxStatus status) {
        return status == AgentAsyncTaskCommandOutboxStatus.FAILED
                || status == AgentAsyncTaskCommandOutboxStatus.BLOCKED
                || status == AgentAsyncTaskCommandOutboxStatus.DEAD_LETTER;
    }

    private PlatformBusinessException conflict(String outboxId,
                                               AgentAsyncTaskCommandOutboxStatus currentStatus,
                                               String action) {
        return new PlatformBusinessException(
                PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                "当前 Agent 异步命令 outbox 状态不允许执行人工补偿动作，action=" + action
                        + ", outboxId=" + outboxId
                        + ", status=" + currentStatus
        );
    }

    private boolean looksSensitive(String value) {
        String lower = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return lower.contains("select ")
                || lower.contains("insert ")
                || lower.contains("update ")
                || lower.contains("delete ")
                || lower.contains("authorization:")
                || lower.contains("bearer ")
                || lower.contains("password")
                || lower.contains("secret")
                || lower.contains("prompt:")
                || lower.contains("stdout")
                || lower.contains("stderr")
                || lower.contains("http://")
                || lower.contains("https://")
                || lower.contains("jdbc:");
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    @FunctionalInterface
    private interface StatusPredicate {
        boolean test(AgentAsyncTaskCommandOutboxStatus status);
    }

    @FunctionalInterface
    private interface CommandOutboxMutation {
        Optional<AgentAsyncTaskCommandOutboxRecord> apply(String outboxId, String reason, Instant now);
    }
}
