/**
 * @Author : Cui
 * @Date: 2026/05/28 02:10
 * @Description DataSmart Govern Backend - AgentToolExecutionEventProjectionSink.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.event;

import com.czh.datasmart.govern.agent.model.AgentToolExecutionState;
import com.czh.datasmart.govern.agent.service.audit.AgentToolExecutionAuditRecord;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventProjectionRecord;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventProjectionStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将 Java 工具执行状态事件写入 Agent runtime event 热投影的 sink。
 *
 * <p>4.10 之前，Python AI Runtime 已经能把模型规划、loop 决策、二轮推理等事件写入
 * runtime-event 投影；4.11 又让 Java 控制面具备了工具状态事件发布契约。本类负责把两条线接起来：
 * Java 工具状态变化不再只停留在 Kafka 或日志里，而是同步进入已有的
 * {@link AgentRuntimeEventProjectionStore}，从而可以被
 * {@code /agent-runtime/runtime-events} 和 {@code /api/agent/runtime-events} 查询与回放。</p>
 *
 * <p>这样做的产品价值很直接：用户打开一次 Agent Run 详情页时，既能看到 Python 模型如何规划，
 * 也能看到 Java 控制面如何审批、执行、成功或失败；网关 WebSocket 断线重连后，也可以先查询这份
 * 热投影补齐最近状态，再继续订阅实时事件。</p>
 *
 * <p>当前实现仍是 JVM 内存热窗口，不等于长期审计事实。生产级路线应继续演进为：
 * 工具状态落库 + outbox 事件 + 持久化投影/审计中心 + 查询索引。这里先接入热投影，是为了在不引入
 * 新表和新中间件复杂度的前提下，把事件驱动 Agent loop 的控制面形态跑通。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentToolExecutionEventProjectionSink implements AgentToolExecutionEventSink {

    /**
     * Agent runtime event 控制面投影仓储。
     *
     * <p>当前默认实现是内存热窗口，已经具备 identityKey 去重、runId 索引、按租户/项目/角色过滤查询等能力。
     * 本 sink 复用它而不是新建一套工具事件查询仓储，可以让前端、gateway、审计和 Python Runtime 后续都围绕
     * 同一个事件回放入口协作。</p>
     */
    private final AgentRuntimeEventProjectionStore projectionStore;

    /**
     * 接收工具状态变化事件并写入 runtime-event 投影。
     *
     * <p>这里不重新读取内存审计仓库，也不调用下游业务服务；它只做事件视图转换。
     * 这种单向转换能避免“投影写入又反过来修改业务状态”的耦合风险，后续即使投影失败也不会影响
     * 工具状态机已经完成的状态推进。</p>
     */
    @Override
    public void accept(AgentToolExecutionState previousState,
                       AgentToolExecutionAuditRecord record,
                       AgentToolExecutionStateChangedEvent event) {
        AgentRuntimeEventProjectionRecord projectionRecord = toProjectionRecord(event);
        boolean appended = projectionStore.append(projectionRecord);
        if (!appended) {
            log.debug("Agent 工具执行状态事件投影已存在，跳过去重写入，identityKey={}, auditId={}, state={}",
                    projectionRecord.identityKey(), event.auditId(), event.currentState());
        }
    }

    /**
     * 将工具状态事件转换成 runtime-event 投影记录。
     *
     * <p>转换时有三个关键设计点：</p>
     * <p>1. identityKey 直接使用工具事件 eventId，保证同一条状态事实重复发布时能被投影仓库去重。</p>
     * <p>2. schemaVersion/eventType 保留原工具事件契约，避免查询侧无法区分 Python runtime event 与 Java tool event。</p>
     * <p>3. attributes 只放适合控制面展示和排障的安全字段，不写入完整 planArguments、审批备注原文或工具原始输出。</p>
     */
    private AgentRuntimeEventProjectionRecord toProjectionRecord(AgentToolExecutionStateChangedEvent event) {
        Instant now = Instant.now();
        Instant occurredAt = event.occurredAt() == null ? now : event.occurredAt();
        return new AgentRuntimeEventProjectionRecord(
                event.eventId(),
                event.schemaVersion(),
                event.source(),
                event.eventType(),
                stageOf(event.currentState()),
                messageOf(event),
                severityOf(event.currentState()),
                event.tenantId(),
                event.projectId(),
                event.actorId(),
                event.traceId(),
                event.runId(),
                event.sessionId(),
                null,
                occurredAt,
                occurredAt,
                now,
                attributesOf(event)
        );
    }

    /**
     * 把工具状态映射成更适合运行详情页展示的阶段名。
     *
     * <p>eventType 保留统一的 {@code agent.tool_execution.state_changed}，stage 则承担“当前状态更像哪类进度节点”
     * 的展示职责。这样查询侧既能按统一事件类型筛选所有工具状态事件，也能按 stage 快速判断是等待审批、执行中、
     * 完成还是失败。</p>
     */
    private String stageOf(String currentState) {
        return switch (normalizeState(currentState)) {
            case "WAITING_APPROVAL" -> "approval_waiting";
            case "PLANNED" -> "tool_planned";
            case "EXECUTING" -> "tool_executing";
            case "SUCCEEDED" -> "tool_completed";
            case "FAILED" -> "tool_failed";
            case "SKIPPED" -> "tool_skipped";
            case "CANCELLED" -> "tool_cancelled";
            default -> "tool_status_changed";
        };
    }

    /**
     * 将状态映射为控制面严重级别。
     *
     * <p>这里的 severity 不是日志级别，而是产品视角的事件重要性：失败/取消需要运维和用户重点关注；
     * 等待审批/跳过属于审计型状态；规划、执行中和成功则是普通进度事件。</p>
     */
    private String severityOf(String currentState) {
        return switch (normalizeState(currentState)) {
            case "FAILED", "CANCELLED" -> "error";
            case "WAITING_APPROVAL", "SKIPPED" -> "audit";
            default -> "info";
        };
    }

    /**
     * 生成投影展示消息。
     *
     * <p>优先使用业务状态机写入的 message，因为它通常包含“为什么等待审批/为什么失败”的业务原因；
     * 如果 message 为空，则退化为通用状态变化描述，保证前端列表不会出现空白事件。</p>
     */
    private String messageOf(AgentToolExecutionStateChangedEvent event) {
        if (hasText(event.message())) {
            return event.message();
        }
        return "工具 " + event.toolCode() + " 状态已从 "
                + event.previousState() + " 变更为 " + event.currentState();
    }

    /**
     * 生成投影 attributes。
     *
     * <p>attributes 是控制面最容易“越长越危险”的区域，因此这里采用安全白名单思路：
     * 放标识、状态、治理标志、风险等级、参数校验摘要和键名列表，不放完整入参、审批备注原文、工具完整输出。
     * 如果未来审计中心需要完整 payload，应通过受权限保护的审计存储或对象引用读取，而不是扩大 runtime-event
     * 热投影的公开字段。</p>
     */
    private Map<String, Object> attributesOf(AgentToolExecutionStateChangedEvent event) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("eventId", event.eventId());
        attributes.put("auditId", event.auditId());
        attributes.put("bindingId", event.bindingId());
        attributes.put("workspaceId", event.workspaceId());
        attributes.put("toolCode", event.toolCode());
        attributes.put("toolType", event.toolType());
        attributes.put("targetService", event.targetService());
        attributes.put("targetEndpoint", event.targetEndpoint());
        attributes.put("targetResourceId", event.targetResourceId());
        attributes.put("previousState", event.previousState());
        attributes.put("currentState", event.currentState());
        attributes.put("riskLevel", event.riskLevel());
        attributes.put("executionMode", event.executionMode());
        attributes.put("requiresApproval", event.requiresApproval());
        attributes.put("readOnly", event.readOnly());
        attributes.put("idempotent", event.idempotent());
        attributes.put("allowedActions", event.allowedActions());
        attributes.put("approvalOperatorId", event.approvalOperatorId());
        attributes.put("approvalCommentPresent", event.approvalCommentPresent());
        attributes.put("resultSummaryPresent", hasText(event.outputSummary()));
        attributes.put("errorCode", event.errorCode());
        attributes.put("planReasonPresent", hasText(event.planReason()));
        attributes.put("planArgumentKeys", event.attributes().getOrDefault("planArgumentKeys", List.of()));
        attributes.put("hasPlanArguments", event.attributes().getOrDefault("hasPlanArguments", false));
        attributes.put("terminalState", event.attributes().getOrDefault("terminalState", false));
        attributes.put("governanceHintKeys", keysOf(event.governanceHints()));
        attributes.put("parameterValidation", event.parameterValidation());
        return attributes;
    }

    private List<String> keysOf(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.keySet().stream()
                .filter(this::hasText)
                .sorted()
                .toList();
    }

    private String normalizeState(String state) {
        return state == null ? "" : state.trim().toUpperCase();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
