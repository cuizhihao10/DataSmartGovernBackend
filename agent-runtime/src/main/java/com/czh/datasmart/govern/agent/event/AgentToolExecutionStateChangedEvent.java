/**
 * @Author : Cui
 * @Date: 2026/05/28 00:58
 * @Description DataSmart Govern Backend - AgentToolExecutionStateChangedEvent.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.event;

import com.czh.datasmart.govern.agent.model.AgentToolExecutionState;
import com.czh.datasmart.govern.agent.service.audit.AgentToolExecutionAuditRecord;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 工具执行状态变更事件。
 *
 * <p>该事件是 Java 控制面向外发布工具执行事实的稳定契约。它面向的消费者不只是前端：
 * Python AI Runtime 可以用它判断二次推理是否要等待工具结果，智能网关可以用它推送 WebSocket 进度，
 * observability 可以用它统计工具成功率、失败率和耗时，audit-center 可以用它形成长期审计链。</p>
 *
 * <p>事件设计有两个核心原则：
 * 1. 只发布“已经发生的事实”，例如状态已经从 EXECUTING 变成 SUCCEEDED，而不是发布“准备要成功”的意图；
 * 2. 默认不携带完整工具入参和审批备注原文，避免把用户输入、业务目标、敏感字段或数据源细节扩散到通用事件总线上。</p>
 *
 * <p>字段没有使用嵌套对象，是为了让 Kafka、ClickHouse、日志检索、前端调试和未来跨语言消费者都更容易处理。
 * 如果后续事件体继续增长，可以按 {@code schemaVersion} 升级到 v2，再把不同业务域拆成子对象。</p>
 */
public record AgentToolExecutionStateChangedEvent(
        String schemaVersion,
        String eventId,
        String eventType,
        String source,
        Instant occurredAt,
        String tenantId,
        String projectId,
        String workspaceId,
        String actorId,
        String sessionId,
        String runId,
        String auditId,
        String bindingId,
        String toolCode,
        String toolType,
        String targetService,
        String targetEndpoint,
        String targetResourceId,
        String previousState,
        String currentState,
        String riskLevel,
        String executionMode,
        Boolean requiresApproval,
        Boolean readOnly,
        Boolean idempotent,
        List<String> allowedActions,
        String traceId,
        String message,
        String approvalOperatorId,
        Boolean approvalCommentPresent,
        String outputSummary,
        String errorCode,
        String planReason,
        Map<String, Object> governanceHints,
        Map<String, Object> parameterValidation,
        Map<String, Object> attributes
) {

    /**
     * 当前事件契约版本。
     *
     * <p>事件版本必须显式写在 payload 内，而不能只依赖 topic 名称。原因是生产环境中 topic 的保留周期可能很长，
     * 消费者在升级期间可能同时读到旧版本和新版本事件，必须能够根据 schemaVersion 做兼容处理。</p>
     */
    public static final String SCHEMA_VERSION = "agent-tool-execution-event.v1";

    /**
     * 统一事件类型。
     *
     * <p>这里不把 EXECUTING、SUCCEEDED、FAILED 拆成多个 eventType，而是用 currentState 表达状态。
     * 好处是消费侧可以用一个处理器订阅完整状态机，再按状态分支处理；未来如果增加 PAUSED、RETRYING、
     * COMPENSATING 等状态，也不需要新增一堆 topic 或消费者入口。</p>
     */
    public static final String EVENT_TYPE = "agent.tool_execution.state_changed";

    public AgentToolExecutionStateChangedEvent {
        allowedActions = allowedActions == null ? List.of() : List.copyOf(allowedActions);
        governanceHints = immutableJsonMap(governanceHints);
        parameterValidation = immutableJsonMap(parameterValidation);
        attributes = immutableJsonMap(attributes);
    }

    private static Map<String, Object> immutableJsonMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    /**
     * 从审计记录构建事件。
     *
     * <p>该工厂方法集中处理字段清洗、时间转换、幂等 ID、敏感入参保护等规则。业务服务不要自己拼 Map，
     * 否则不同状态入口很容易产生字段不一致的问题。</p>
     *
     * @param source 发布事件的服务名，例如 agent-runtime。
     * @param previousState 变更前状态；创建初始计划时可为空。
     * @param record 已经完成状态推进的审计记录。
     * @return 可以序列化为 JSON 并投递到消息系统的状态变更事件。
     */
    public static AgentToolExecutionStateChangedEvent from(String source,
                                                           AgentToolExecutionState previousState,
                                                           AgentToolExecutionAuditRecord record) {
        String previousStateName = previousState == null ? "NONE" : previousState.name();
        String currentStateName = record.getState() == null ? "UNKNOWN" : record.getState().name();
        Instant occurredAt = toInstant(record.getUpdateTime());
        return new AgentToolExecutionStateChangedEvent(
                SCHEMA_VERSION,
                buildEventId(record, previousStateName, currentStateName),
                EVENT_TYPE,
                source,
                occurredAt,
                toStringId(record.getTenantId()),
                toStringId(record.getProjectId()),
                toStringId(record.getWorkspaceId()),
                record.getActorId(),
                record.getSessionId(),
                record.getRunId(),
                record.getAuditId(),
                record.getBindingId(),
                record.getToolCode(),
                record.getToolType(),
                record.getTargetService(),
                record.getTargetEndpoint(),
                toStringId(record.getTargetResourceId()),
                previousStateName,
                currentStateName,
                record.getRiskLevel(),
                record.getExecutionMode(),
                record.getRequiresApproval(),
                record.getReadOnly(),
                record.getIdempotent(),
                record.getAllowedActions(),
                record.getTraceId(),
                record.getMessage(),
                record.getApprovalOperatorId(),
                hasText(record.getApprovalComment()),
                record.getOutputSummary(),
                record.getErrorCode(),
                record.getPlanReason(),
                record.getGovernanceHints(),
                record.getParameterValidation(),
                buildAttributes(record)
        );
    }

    /**
     * Kafka 分区键。
     *
     * <p>同一个 run 的状态事件应该尽量进入同一分区，这样消费侧更容易按顺序重建工具执行过程。
     * 如果 runId 为空，则逐级降级到 sessionId/auditId，避免 key 为空导致事件随机分散。</p>
     */
    public String partitionKey() {
        if (runId != null && !runId.isBlank()) {
            return runId;
        }
        if (sessionId != null && !sessionId.isBlank()) {
            return sessionId;
        }
        return auditId;
    }

    private static String buildEventId(AgentToolExecutionAuditRecord record,
                                       String previousStateName,
                                       String currentStateName) {
        String transitionTime = record.getUpdateTime() == null
                ? "unknown-time"
                : record.getUpdateTime().toString();
        return "agent-tool-execution:"
                + record.getAuditId()
                + ":"
                + previousStateName
                + "->"
                + currentStateName
                + ":"
                + transitionTime;
    }

    private static Map<String, Object> buildAttributes(AgentToolExecutionAuditRecord record) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("planArgumentKeys", planArgumentKeys(record.getPlanArguments()));
        attributes.put("hasPlanArguments", record.getPlanArguments() != null && !record.getPlanArguments().isEmpty());
        attributes.put("hasOutputSummary", record.getOutputSummary() != null && !record.getOutputSummary().isBlank());
        attributes.put("terminalState", isTerminalState(record.getState()));
        return attributes;
    }

    private static List<String> planArgumentKeys(Map<String, Object> planArguments) {
        if (planArguments == null || planArguments.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(planArguments.keySet());
    }

    private static boolean isTerminalState(AgentToolExecutionState state) {
        return state == AgentToolExecutionState.SUCCEEDED
                || state == AgentToolExecutionState.FAILED
                || state == AgentToolExecutionState.SKIPPED
                || state == AgentToolExecutionState.CANCELLED;
    }

    private static Instant toInstant(LocalDateTime localDateTime) {
        if (localDateTime == null) {
            return Instant.now();
        }
        return localDateTime.atZone(ZoneId.systemDefault()).toInstant();
    }

    private static String toStringId(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
