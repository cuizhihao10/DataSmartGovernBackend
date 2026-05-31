/**
 * @Author : Cui
 * @Date: 2026/05/31 17:20
 * @Description DataSmart Govern Backend - AgentRunAsyncTaskCommandOutboxService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.execution;

import com.czh.datasmart.govern.agent.config.AgentAsyncTaskCommandOutboxProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentAsyncTaskCommandOutboxRecordView;
import com.czh.datasmart.govern.agent.controller.dto.AgentAsyncTaskCommandPlanItemView;
import com.czh.datasmart.govern.agent.controller.dto.AgentAsyncTaskCommandOutboxQueryResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunAsyncTaskCommandOutboxEnqueueResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunAsyncTaskCommandPlanView;
import com.czh.datasmart.govern.agent.event.command.AgentAsyncTaskCommandOutboxRecord;
import com.czh.datasmart.govern.agent.event.command.AgentAsyncTaskCommandOutboxStatus;
import com.czh.datasmart.govern.agent.event.command.AgentAsyncTaskCommandOutboxStore;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent Run 异步命令 outbox 服务。
 *
 * <p>该服务把 4.45 的只读 command plan 推进为“可恢复投递记录”。
 * 它仍然不会直接创建 task-management 任务，也不会直接调用 Kafka。
 * 它只负责把可下发的 command envelope 写入 outbox，后续 dispatcher 再异步投递。</p>
 *
 * <p>这样做是为了解决经典双写问题：如果业务线程先改工具审计状态再直接发 Kafka，一旦 Kafka 发送失败，
 * Java 控制面就已经认为命令下发了，但 task-management 实际没有收到。outbox 让“准备下发”先变成可查询事实，
 * 再通过 dispatcher 重试、阻断或人工补偿。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentRunAsyncTaskCommandOutboxService {

    private final AgentAsyncTaskCommandOutboxProperties properties;
    private final AgentRunAsyncTaskCommandPlanningService planningService;
    private final AgentAsyncTaskCommandOutboxStore outboxStore;
    private final ObjectMapper objectMapper;

    /**
     * 将某次 Run 中可下发的 ASYNC_TASK command 写入 outbox。
     *
     * @param sessionId Agent 会话 ID。
     * @param runId Agent Run ID。
     * @return 入箱结果，包含首次写入、重复复用和阻断数量。
     */
    public AgentRunAsyncTaskCommandOutboxEnqueueResponse enqueueRunAsyncTaskCommands(String sessionId, String runId) {
        ensureEnabled();
        AgentRunAsyncTaskCommandPlanView plan = planningService.planRunAsyncTaskCommands(sessionId, runId);
        List<AgentAsyncTaskCommandOutboxRecordView> views = new ArrayList<>();
        int enqueued = 0;
        int duplicate = 0;
        int blocked = 0;
        for (AgentAsyncTaskCommandPlanItemView item : plan.items()) {
            if (!Boolean.TRUE.equals(item.dispatchable())) {
                blocked++;
                continue;
            }
            AgentAsyncTaskCommandOutboxRecord record = buildRecord(plan, item);
            boolean appended = outboxStore.append(record);
            AgentAsyncTaskCommandOutboxRecord current = appended
                    ? record
                    : outboxStore.findByCommandId(item.commandId()).orElse(record);
            if (appended) {
                enqueued++;
            } else {
                duplicate++;
            }
            views.add(AgentAsyncTaskCommandOutboxRecordView.from(current));
        }
        return new AgentRunAsyncTaskCommandOutboxEnqueueResponse(
                sessionId,
                runId,
                plan.totalAsyncTools(),
                plan.dispatchableCount(),
                enqueued,
                duplicate,
                blocked,
                summaryReasons(plan, enqueued, duplicate, blocked),
                recommendedActions(enqueued, duplicate, blocked),
                views
        );
    }

    /**
     * 查询 command outbox 记录。
     */
    public AgentAsyncTaskCommandOutboxQueryResponse query(String runId, String status, Integer limit) {
        AgentAsyncTaskCommandOutboxStatus parsedStatus = parseStatus(status);
        int normalizedLimit = normalizeLimit(limit);
        List<AgentAsyncTaskCommandOutboxRecordView> views = outboxStore.list(normalizeText(runId), parsedStatus, normalizedLimit)
                .stream()
                .map(AgentAsyncTaskCommandOutboxRecordView::from)
                .toList();
        return new AgentAsyncTaskCommandOutboxQueryResponse(
                normalizeText(runId),
                parsedStatus == null ? null : parsedStatus.name(),
                normalizedLimit,
                views.size(),
                views
        );
    }

    private AgentAsyncTaskCommandOutboxRecord buildRecord(AgentRunAsyncTaskCommandPlanView plan,
                                                          AgentAsyncTaskCommandPlanItemView item) {
        Instant now = Instant.now();
        String payloadReference = payloadReference(plan.sessionId(), plan.runId(), item.auditId());
        String payloadJson = toJson(payload(plan, item, payloadReference));
        int payloadBytes = payloadJson.getBytes(StandardCharsets.UTF_8).length;
        if (payloadBytes > Math.max(1, properties.getMaxPayloadBytes())) {
            throw new PlatformBusinessException(
                    PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "Agent 异步命令 payload 超过安全上限，commandId=" + item.commandId()
                            + ", payloadBytes=" + payloadBytes
            );
        }
        return AgentAsyncTaskCommandOutboxRecord.pending(
                item.commandId(),
                item.idempotencyKey(),
                properties.getSchemaVersion(),
                item.commandType(),
                item.commandTopic(),
                item.consumerService(),
                plan.sessionId(),
                plan.runId(),
                item.auditId(),
                item.toolCode(),
                item.targetService(),
                item.targetEndpoint(),
                item.tenantId(),
                item.projectId(),
                item.workspaceId(),
                item.actorId(),
                item.traceId(),
                payloadReference,
                payloadJson,
                payloadBytes,
                now
        );
    }

    /**
     * 构造发送给 task-management 的安全命令 payload。
     *
     * <p>这里使用白名单 Map，而不是直接序列化 plan item。这样即使 plan DTO 未来新增了某些诊断字段、
     * 原始参数摘要或内部原因，也不会自动进入跨服务命令。</p>
     */
    private Map<String, Object> payload(AgentRunAsyncTaskCommandPlanView plan,
                                        AgentAsyncTaskCommandPlanItemView item,
                                        String payloadReference) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", properties.getSchemaVersion());
        payload.put("commandId", item.commandId());
        payload.put("idempotencyKey", item.idempotencyKey());
        payload.put("commandType", item.commandType());
        payload.put("auditId", item.auditId());
        payload.put("sessionId", plan.sessionId());
        payload.put("runId", plan.runId());
        payload.put("toolCode", item.toolCode());
        payload.put("targetService", item.targetService());
        payload.put("targetEndpoint", item.targetEndpoint());
        payload.put("tenantId", item.tenantId());
        payload.put("projectId", item.projectId());
        payload.put("workspaceId", item.workspaceId());
        payload.put("actorId", item.actorId());
        payload.put("traceId", item.traceId());
        payload.put("payloadReference", payloadReference);
        payload.put("argumentNames", item.argumentNames());
        payload.put("sensitiveArgumentNames", item.sensitiveArgumentNames());
        payload.put("priority", properties.getDefaultPriority());
        payload.put("maxRetryCount", properties.getDefaultMaxRetryCount());
        payload.put("maxDeferCount", properties.getDefaultMaxDeferCount());
        return payload;
    }

    private String payloadReference(String sessionId, String runId, String auditId) {
        return "agent-tool-audit://" + sessionId + "/" + runId + "/" + auditId + "/plan-arguments";
    }

    private List<String> summaryReasons(AgentRunAsyncTaskCommandPlanView plan,
                                        int enqueued,
                                        int duplicate,
                                        int blocked) {
        List<String> reasons = new ArrayList<>();
        reasons.addAll(plan.summaryReasons());
        if (enqueued > 0) {
            reasons.add("已有异步命令首次进入 outbox，等待 dispatcher 投递到 task-management。");
        }
        if (duplicate > 0) {
            reasons.add("存在已入箱命令，本次按 commandId 幂等复用，没有重复创建 outbox 记录。");
        }
        if (blocked > 0) {
            reasons.add("存在未满足下发条件的异步工具，未写入 outbox。");
        }
        return reasons;
    }

    private List<String> recommendedActions(int enqueued, int duplicate, int blocked) {
        List<String> actions = new ArrayList<>();
        if (enqueued > 0 || duplicate > 0) {
            actions.add("调用 command outbox dispatch-once 或开启 dispatcher，将命令投递到 task-management Inbox。");
        }
        if (blocked > 0) {
            actions.add("先处理审批、参数补全、幂等声明或 Run 终态阻断，再重新入箱。");
        }
        actions.add("后续应接入 Kafka producer 与 MySQL outbox，使命令投递具备跨实例恢复能力。");
        return actions;
    }

    private AgentAsyncTaskCommandOutboxStatus parseStatus(String status) {
        String normalized = normalizeText(status);
        if (normalized == null) {
            return null;
        }
        try {
            return AgentAsyncTaskCommandOutboxStatus.valueOf(normalized.toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST, "不支持的异步命令 outbox 状态: " + status);
        }
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return 100;
        }
        return Math.min(limit, 1000);
    }

    private String normalizeText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new PlatformBusinessException(
                    PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "Agent 异步命令 payload 序列化失败: " + exception.getMessage()
            );
        }
    }

    private void ensureEnabled() {
        if (!properties.isEnabled()) {
            throw new PlatformBusinessException(
                    PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "Agent 异步命令 outbox 当前未启用"
            );
        }
    }
}
