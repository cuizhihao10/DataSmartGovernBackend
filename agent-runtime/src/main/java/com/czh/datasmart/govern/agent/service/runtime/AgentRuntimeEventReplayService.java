/**
 * @Author : Cui
 * @Date: 2026/06/01 00:02
 * @Description DataSmart Govern Backend - AgentRuntimeEventReplayService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentRuntimeEventProjectionQueryResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentRuntimeEventReplayAckRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentRuntimeEventReplayCursorView;
import com.czh.datasmart.govern.agent.controller.dto.AgentRuntimeEventReplayResponse;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

/**
 * Agent runtime event replay 与 ack cursor 服务。
 *
 * <p>该服务是 WebSocket 实时事件能力的“可恢复控制面底座”。它本身不直接维护 WebSocket 连接，
 * 也不负责把事件推送到浏览器；它负责两件更底层、更稳定的事：</p>
 *
 * <p>1. replay：按 runId/sessionId 与 afterSequence 查询 Java 控制面投影事件；</p>
 * <p>2. ack：记录客户端已经消费到的最大 replaySequence，供断线重连或 Python WebSocket 桥接复用。</p>
 *
 * <p>这样分层后，Python Runtime 的 `/agent/events/ws` 可以继续承载长连接协议，Java agent-runtime 则提供可信事件源、
 * 数据范围收口、脱敏、display 展示解释和 ack cursor。未来如果 Java 也要直接承载 WebSocket，只需复用本服务。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentRuntimeEventReplayService {

    private final AgentRuntimeEventProjectionQueryService queryService;
    private final AgentRuntimeEventProjectionAccessSupport accessSupport;
    private final AgentRuntimeEventReplayCursorStore cursorStore;

    /**
     * 生成一次 runtime event replay 响应。
     *
     * <p>replay 要求至少提供 runId 或 sessionId。原因是实时事件回放通常服务于某个会话或某次运行；
     * 如果允许完全不带范围地 replay，很容易把当前内存热窗口中的跨租户、跨项目事件压力都集中到一个接口上。
     * 更宽泛的排障查询仍然走普通 `queryEvents` 接口，并受 limit/data scope/visibility 控制。</p>
     *
     * @param query 原始查询条件。
     * @param accessContext 当前访问上下文。
     * @param clientId 可选客户端 ID；传入后会在未显式 afterSequence 时使用服务端 ack cursor。
     * @return replay 响应，包含事件列表、有效起点和 cursor 摘要。
     */
    public AgentRuntimeEventReplayResponse replay(AgentRuntimeEventProjectionQuery query,
                                                  AgentRuntimeEventQueryAccessContext accessContext,
                                                  String clientId) {
        requireSubscriptionScope(query.runId(), query.sessionId());
        String normalizedClientId = normalizeOptional(clientId);
        String subscriptionKey = subscriptionKey(query.runId(), query.sessionId());
        Optional<AgentRuntimeEventReplayCursorRecord> cursor = normalizedClientId == null
                ? Optional.empty()
                : cursorStore.find(normalizedClientId, subscriptionKey);
        Long effectiveAfterSequence = resolveEffectiveAfterSequence(query.afterSequence(), cursor);

        AgentRuntimeEventProjectionQuery replayQuery = new AgentRuntimeEventProjectionQuery(
                query.tenantId(),
                query.projectId(),
                query.actorId(),
                query.requestId(),
                query.runId(),
                query.sessionId(),
                query.eventType(),
                query.severity(),
                query.limit(),
                effectiveAfterSequence,
                query.authorizedProjectIds()
        );
        AgentRuntimeEventProjectionQueryResponse response = queryService.query(replayQuery, accessContext);
        return new AgentRuntimeEventReplayResponse(
                response.appliedLimit(),
                response.totalMatched(),
                query.afterSequence(),
                effectiveAfterSequence,
                cursor.map(record -> toCursorView(record, null, false, "CURSOR_FOUND")).orElse(null),
                Instant.now(),
                response.events()
        );
    }

    /**
     * 记录客户端 replay ack。
     *
     * <p>ack 会先经过 accessSupport 做数据范围校验，保证普通用户不能给别的租户或越权项目的订阅范围写 cursor。
     * 当前 cursor store 不直接保存完整权限上下文，只保存租户/项目/actor 摘要，避免把权限矩阵复制到 replay 层。</p>
     */
    public AgentRuntimeEventReplayCursorView acknowledge(AgentRuntimeEventReplayAckRequest request,
                                                         AgentRuntimeEventQueryAccessContext accessContext) {
        String clientId = requireText(request == null ? null : request.clientId(), "clientId");
        String runId = normalizeOptional(request.runId());
        String sessionId = normalizeOptional(request.sessionId());
        requireSubscriptionScope(runId, sessionId);
        long ackSequence = requirePositiveSequence(request.acknowledgedReplaySequence());

        AgentRuntimeEventProjectionQuery scopedQuery = accessSupport.restrict(
                new AgentRuntimeEventProjectionQuery(null, null, null, null, runId, sessionId, null, null, 1),
                accessContext
        );
        AgentRuntimeEventReplayCursorRecord candidate = new AgentRuntimeEventReplayCursorRecord(
                clientId,
                subscriptionKey(runId, sessionId),
                scopedQuery.tenantId(),
                scopedQuery.projectId(),
                scopedQuery.actorId(),
                runId,
                sessionId,
                ackSequence,
                Instant.now()
        );
        AgentRuntimeEventReplayCursorStore.CursorAdvanceResult result = cursorStore.saveMax(candidate);
        return toCursorView(
                result.current(),
                result.previous().map(AgentRuntimeEventReplayCursorRecord::acknowledgedReplaySequence).orElse(null),
                result.advanced(),
                result.advanced() ? "ACK_ADVANCED" : "STALE_ACK_IGNORED"
        );
    }

    /**
     * 查询客户端当前 cursor。
     */
    public AgentRuntimeEventReplayCursorView cursor(String clientId,
                                                    String runId,
                                                    String sessionId,
                                                    AgentRuntimeEventQueryAccessContext accessContext) {
        String normalizedClientId = requireText(clientId, "clientId");
        String normalizedRunId = normalizeOptional(runId);
        String normalizedSessionId = normalizeOptional(sessionId);
        requireSubscriptionScope(normalizedRunId, normalizedSessionId);
        accessSupport.restrict(
                new AgentRuntimeEventProjectionQuery(null, null, null, null, normalizedRunId, normalizedSessionId, null, null, 1),
                accessContext
        );
        return cursorStore.find(normalizedClientId, subscriptionKey(normalizedRunId, normalizedSessionId))
                .map(record -> toCursorView(record, null, false, "CURSOR_FOUND"))
                .orElseGet(() -> new AgentRuntimeEventReplayCursorView(
                        normalizedClientId,
                        subscriptionKey(normalizedRunId, normalizedSessionId),
                        normalizedRunId,
                        normalizedSessionId,
                        0L,
                        null,
                        false,
                        "CURSOR_NOT_FOUND",
                        null
                ));
    }

    private Long resolveEffectiveAfterSequence(Long requestedAfterSequence,
                                               Optional<AgentRuntimeEventReplayCursorRecord> cursor) {
        if (requestedAfterSequence != null && requestedAfterSequence > 0) {
            return requestedAfterSequence;
        }
        return cursor.map(AgentRuntimeEventReplayCursorRecord::acknowledgedReplaySequence).orElse(0L);
    }

    private AgentRuntimeEventReplayCursorView toCursorView(AgentRuntimeEventReplayCursorRecord record,
                                                           Long previousAcknowledgedReplaySequence,
                                                           boolean advanced,
                                                           String reason) {
        return new AgentRuntimeEventReplayCursorView(
                record.clientId(),
                record.subscriptionKey(),
                record.runId(),
                record.sessionId(),
                record.acknowledgedReplaySequence(),
                previousAcknowledgedReplaySequence,
                advanced,
                reason,
                record.updatedAt()
        );
    }

    private void requireSubscriptionScope(String runId, String sessionId) {
        if (normalizeOptional(runId) == null && normalizeOptional(sessionId) == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "runtime event replay 必须至少提供 runId 或 sessionId。");
        }
    }

    private long requirePositiveSequence(Long sequence) {
        if (sequence == null || sequence <= 0) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "acknowledgedReplaySequence 必须大于 0。");
        }
        return sequence;
    }

    private String requireText(String value, String fieldName) {
        String normalized = normalizeOptional(value);
        if (normalized == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    fieldName + " 不能为空。");
        }
        return normalized;
    }

    private String subscriptionKey(String runId, String sessionId) {
        String normalizedRunId = normalizeOptional(runId);
        String normalizedSessionId = normalizeOptional(sessionId);
        if (normalizedRunId != null && normalizedSessionId != null) {
            return "run:" + normalizedRunId + "|session:" + normalizedSessionId;
        }
        if (normalizedRunId != null) {
            return "run:" + normalizedRunId;
        }
        return "session:" + normalizedSessionId;
    }

    private String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
