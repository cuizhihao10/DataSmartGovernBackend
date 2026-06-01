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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final AgentAsyncTaskCommandOutboxCapacityGuard capacityGuard;
    private final ObjectMapper objectMapper;

    /**
     * 将某次 Run 中可下发的 ASYNC_TASK command 写入 outbox。
     *
     * @param sessionId Agent 会话 ID。
     * @param runId Agent Run ID。
     * @return 入箱结果，包含首次写入、重复复用和阻断数量。
     */
    public AgentRunAsyncTaskCommandOutboxEnqueueResponse enqueueRunAsyncTaskCommands(String sessionId, String runId) {
        return enqueueRunAsyncTaskCommands(sessionId, runId, null);
    }

    /**
     * 只把指定 auditId 对应的异步命令写入 outbox。
     *
     * <p>该方法是 DAG selected-node dispatcher 的内部收口点。调用方不能直接提交 targetEndpoint、topic
     * 或参数值，只能提交已经由上游 dry-run 重新验证过的 auditId 白名单。服务仍会重新生成 command plan，
     * 再按白名单过滤，因此不会因为前端缓存了一份旧 DTO 就绕过最新审批、状态或幂等判断。</p>
     *
     * @param sessionId Agent 会话 ID。
     * @param runId Agent Run ID。
     * @param selectedAuditIds 已通过 DAG dry-run 确认的异步工具审计 ID。
     * @return 仅统计指定审计 ID 范围的入箱结果。
     */
    public AgentRunAsyncTaskCommandOutboxEnqueueResponse enqueueSelectedRunAsyncTaskCommands(String sessionId,
                                                                                              String runId,
                                                                                              Collection<String> selectedAuditIds) {
        return enqueueSelectedRunAsyncTaskCommands(sessionId, runId, selectedAuditIds, Map.of());
    }

    /**
     * 只把指定 auditId 对应的异步命令写入 outbox，并为 selected-node 确认链路附加执行前证据。
     *
     * <p>该重载由 DAG selected-node confirmation 使用。它不会让调用方传入工具参数或目标地址，只允许附加
     * confirmationId、policyVersion 和 delegationEvidence 这类低敏治理证据。task-management 后续消费 command 时，
     * 可以先复核这份证据，再把任务交给真实 worker。</p>
     */
    public AgentRunAsyncTaskCommandOutboxEnqueueResponse enqueueSelectedRunAsyncTaskCommands(
            String sessionId,
            String runId,
            Collection<String> selectedAuditIds,
            Map<String, AgentAsyncTaskCommandExecutionEvidence> executionEvidenceByAuditId) {
        Set<String> normalizedAuditIds = normalizeAuditIds(selectedAuditIds);
        if (normalizedAuditIds.isEmpty()) {
            throw new PlatformBusinessException(
                    PlatformErrorCode.BAD_REQUEST,
                    "DAG 选中节点入箱至少需要一个已经通过 dry-run 的异步工具 auditId"
            );
        }
        return enqueueRunAsyncTaskCommands(sessionId, runId, normalizedAuditIds,
                executionEvidenceByAuditId == null ? Map.of() : executionEvidenceByAuditId);
    }

    /**
     * 执行 Run 级或白名单范围的异步命令入箱。
     *
     * <p>{@code selectedAuditIds=null} 表示兼容原有 Run 级批量入口；非空时只处理 selected-node 服务传入的
     * 服务端白名单。两种入口共用同一套 payload 构造、容量限制和 outbox 幂等规则，避免后续维护两套投递语义。</p>
     */
    private AgentRunAsyncTaskCommandOutboxEnqueueResponse enqueueRunAsyncTaskCommands(String sessionId,
                                                                                       String runId,
                                                                                       Set<String> selectedAuditIds) {
        return enqueueRunAsyncTaskCommands(sessionId, runId, selectedAuditIds, Map.of());
    }

    private AgentRunAsyncTaskCommandOutboxEnqueueResponse enqueueRunAsyncTaskCommands(
            String sessionId,
            String runId,
            Set<String> selectedAuditIds,
            Map<String, AgentAsyncTaskCommandExecutionEvidence> executionEvidenceByAuditId) {
        ensureEnabled();
        AgentRunAsyncTaskCommandPlanView plan = planningService.planRunAsyncTaskCommands(sessionId, runId);
        List<AgentAsyncTaskCommandPlanItemView> scopedItems = plan.items().stream()
                .filter(item -> selectedAuditIds == null || selectedAuditIds.contains(item.auditId()))
                .toList();
        /*
         * 入箱前先做容量保护，而不是等 append 后再检查。
         * 原因是 append 成功就已经形成“待投递命令”事实，后置检查只能补救，不能阻止积压继续扩大。
         * 这里保护 Run 级积压、租户级积压和单请求批量大小，避免 Agent DAG 自动化在高并发或重试风暴下压垮下游。
         */
        capacityGuard.assertCanEnqueue(runId, scopedItems);
        List<AgentAsyncTaskCommandOutboxRecordView> views = new ArrayList<>();
        int enqueued = 0;
        int duplicate = 0;
        int blocked = 0;
        for (AgentAsyncTaskCommandPlanItemView item : scopedItems) {
            if (!Boolean.TRUE.equals(item.dispatchable())) {
                blocked++;
                continue;
            }
            AgentAsyncTaskCommandOutboxRecord record = buildRecord(plan, item, evidenceFor(item, executionEvidenceByAuditId));
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
                scopedItems.size(),
                (int) scopedItems.stream().filter(AgentAsyncTaskCommandPlanItemView::dispatchable).count(),
                enqueued,
                duplicate,
                blocked,
                summaryReasons(plan, selectedAuditIds != null, enqueued, duplicate, blocked),
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
                                                          AgentAsyncTaskCommandPlanItemView item,
                                                          AgentAsyncTaskCommandExecutionEvidence executionEvidence) {
        Instant now = Instant.now();
        String payloadReference = payloadReference(plan.sessionId(), plan.runId(), item.auditId());
        String payloadJson = toJson(payload(plan, item, payloadReference, executionEvidence));
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
                                        String payloadReference,
                                        AgentAsyncTaskCommandExecutionEvidence executionEvidence) {
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
        if (executionEvidence != null && executionEvidence.confirmationId() != null) {
            payload.put("confirmationId", executionEvidence.confirmationId());
            payload.put("policyVersions", executionEvidence.policyVersions());
            payload.put("delegationEvidence", executionEvidence.delegationEvidence());
        }
        payload.put("priority", properties.getDefaultPriority());
        payload.put("maxRetryCount", properties.getDefaultMaxRetryCount());
        payload.put("maxDeferCount", properties.getDefaultMaxDeferCount());
        return payload;
    }

    private AgentAsyncTaskCommandExecutionEvidence evidenceFor(
            AgentAsyncTaskCommandPlanItemView item,
            Map<String, AgentAsyncTaskCommandExecutionEvidence> executionEvidenceByAuditId) {
        if (executionEvidenceByAuditId == null || item == null || item.auditId() == null) {
            return AgentAsyncTaskCommandExecutionEvidence.empty();
        }
        return executionEvidenceByAuditId.getOrDefault(item.auditId(), AgentAsyncTaskCommandExecutionEvidence.empty());
    }

    private String payloadReference(String sessionId, String runId, String auditId) {
        return "agent-tool-audit://" + sessionId + "/" + runId + "/" + auditId + "/plan-arguments";
    }

    private List<String> summaryReasons(AgentRunAsyncTaskCommandPlanView plan,
                                        boolean selectedOnly,
                                        int enqueued,
                                        int duplicate,
                                        int blocked) {
        List<String> reasons = new ArrayList<>();
        if (selectedOnly) {
            reasons.add("当前 outbox 入箱仅处理 DAG dry-run 已确认的异步 auditId 白名单，不会把同一 Run 的其他异步工具顺带入箱。");
        } else {
            reasons.addAll(plan.summaryReasons());
        }
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

    /**
     * 规范化内部 auditId 白名单。
     *
     * <p>虽然 selected-node 服务传入的 ID 已来自服务端 dry-run，但这里仍做去空、去重和 trim。
     * 这种防御式校验让 outbox 服务未来被其他内部编排器复用时，也不会因为空 ID 或重复 ID 产生歧义。</p>
     */
    private Set<String> normalizeAuditIds(Collection<String> auditIds) {
        if (auditIds == null || auditIds.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String auditId : auditIds) {
            if (auditId != null && !auditId.isBlank()) {
                normalized.add(auditId.trim());
            }
        }
        return Set.copyOf(normalized);
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
