/**
 * @Author : Cui
 * @Date: 2026/08/15
 * @Description DataSmart Govern Backend - SyncExecutionLifecycleGraphService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.entity.SyncAgentExecutionCorrelation;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.integration.agent.AgentRuntimeAuditObservation;
import com.czh.datasmart.govern.datasync.integration.agent.AgentRuntimeCommandObservation;
import com.czh.datasmart.govern.datasync.integration.agent.HttpAgentRuntimeAuditObservationClient;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 聚合一次同步执行的用户目标、Agent、Kafka、Java 审计、worker、Recovery 与最终验证状态。
 *
 * <p>所有状态仍由原系统拥有：Agent Runtime 管工具审计，data-sync execution 管 worker，Recovery case/outbox
 * 管自治恢复。本服务只做只读投影。某个来源不存在或暂时不可用时，它会返回 PARTIAL/NOT_RECORDED，绝不通过
 * 推测补齐“成功”节点，也不会因为画图而触发重试、恢复或状态迁移。</p>
 */
@Service
@RequiredArgsConstructor
public class SyncExecutionLifecycleGraphService {

    private static final String SCHEMA_VERSION = "1.1";

    private final SyncAutopilotRecoveryStatusQueryService recoveryStatusQueryService;
    private final SyncAgentExecutionCorrelationSupport correlationSupport;
    private final HttpAgentRuntimeAuditObservationClient auditObservationClient;
    private final SyncExecutionMapper executionMapper;

    /**
     * 生成一次 execution 的统一全链路图。
     *
     * <p>调用方必须先通过 {@code DataSyncService.getTask} 取得 visibleTask。Recovery 查询服务会再次校验
     * execution 与任务、租户和项目归属，避免把其他任务的 execution 拼接到当前图中。</p>
     *
     * @param visibleTask 已完成数据范围校验的同步任务
     * @param executionId 用户正在查看的根 execution
     * @param actorContext 当前请求上下文，只用于受信内部审计查询和 trace 透传
     * @return 不含敏感正文的统一状态图
     */
    public SyncExecutionLifecycleGraphView query(SyncTask visibleTask,
                                                 Long executionId,
                                                 SyncActorContext actorContext) {
        SyncAutopilotRecoveryStatusView recovery = recoveryStatusQueryService.query(visibleTask, executionId);
        Long rootExecutionId = recovery.rootExecutionId() == null ? executionId : recovery.rootExecutionId();
        Long currentExecutionId = recovery.currentExecutionId() == null ? executionId : recovery.currentExecutionId();
        SyncAgentExecutionCorrelation correlation = correlationSupport.findLatest(
                visibleTask.getTenantId(), visibleTask.getId(), rootExecutionId);
        AgentRuntimeAuditObservation audit = observeAudit(correlation, actorContext);
        AgentRuntimeCommandObservation command = observeCommand(correlation, actorContext);
        SyncExecution rootExecution = rootExecution(rootExecutionId, currentExecutionId);

        List<SyncExecutionLifecycleGraphView.LifecycleEvidence> evidence = buildEvidence(
                visibleTask, rootExecutionId, currentExecutionId, rootExecution,
                correlation, audit, command, recovery);
        List<SyncExecutionLifecycleGraphView.LifecycleNode> nodes = buildNodes(
                visibleTask, rootExecutionId, currentExecutionId, rootExecution,
                correlation, audit, command, recovery);
        List<SyncExecutionLifecycleGraphView.LifecycleEdge> edges = buildEdges(nodes);
        SourceCompleteness completeness = sourceCompleteness(correlation, audit, command);
        return new SyncExecutionLifecycleGraphView(
                SCHEMA_VERSION,
                "SYNC_EXECUTION_LIFECYCLE",
                true,
                visibleTask.getId(),
                rootExecutionId,
                currentExecutionId,
                finalState(recovery),
                completeness.status(),
                completeness.reason(),
                List.copyOf(nodes),
                List.copyOf(edges),
                List.copyOf(evidence),
                LocalDateTime.now()
        );
    }

    /** 仅在存在持久化 Agent 关联时访问 Java 审计；手工执行不会被误查或误标为 Agent 触发。 */
    private AgentRuntimeAuditObservation observeAudit(SyncAgentExecutionCorrelation correlation,
                                                      SyncActorContext actorContext) {
        if (correlation == null) {
            return AgentRuntimeAuditObservation.unavailable("AGENT_EXECUTION_NOT_LINKED");
        }
        return auditObservationClient.observe(
                correlation.getSessionId(), correlation.getRunId(), correlation.getAuditId(), actorContext);
    }

    /** 只为异步命令入口查询 command outbox；直接工具入口明确没有初始命令。 */
    private AgentRuntimeCommandObservation observeCommand(SyncAgentExecutionCorrelation correlation,
                                                          SyncActorContext actorContext) {
        if (correlation == null) {
            return AgentRuntimeCommandObservation.unavailable("AGENT_EXECUTION_NOT_LINKED");
        }
        if (directEntry(correlation)) {
            return AgentRuntimeCommandObservation.unavailable("DIRECT_AGENT_TOOL_WITHOUT_INITIAL_COMMAND");
        }
        return auditObservationClient.observeCommand(
                correlation.getSessionId(), correlation.getRunId(), correlation.getCommandId(), actorContext);
    }

    /** 只有恢复创建了新 execution 时才额外读取根 execution，避免常规查询增加一次数据库访问。 */
    private SyncExecution rootExecution(Long rootExecutionId, Long currentExecutionId) {
        if (Objects.equals(rootExecutionId, currentExecutionId)) {
            return null;
        }
        return executionMapper.selectById(rootExecutionId);
    }

    /** 构造按固定业务顺序排列的节点，前端不需要重新推断跨服务先后关系。 */
    private List<SyncExecutionLifecycleGraphView.LifecycleNode> buildNodes(
            SyncTask task,
            Long rootExecutionId,
            Long currentExecutionId,
            SyncExecution rootExecution,
            SyncAgentExecutionCorrelation correlation,
            AgentRuntimeAuditObservation audit,
            AgentRuntimeCommandObservation command,
            SyncAutopilotRecoveryStatusView recovery) {
        List<SyncExecutionLifecycleGraphView.LifecycleNode> nodes = new ArrayList<>();
        String correlationEvidence = correlation == null ? null : "correlation:" + correlation.getId();
        String auditEvidence = correlation == null ? null : "agent-audit:" + correlation.getAuditId();
        String rootWorkerEvidence = "sync-execution:" + rootExecutionId;
        String currentWorkerEvidence = "sync-execution:" + currentExecutionId;
        String recoveryKafkaEvidence = hasRecoveryKafkaFact(recovery)
                ? "autopilot-outbox:" + recovery.rootExecutionId() : null;
        String recoveryEvidence = recovery.caseId() == null
                ? recoveryKafkaEvidence : "recovery-case:" + recovery.caseId();

        nodes.add(node("user-goal", "USER_GOAL", null,
                correlation == null ? "NOT_LINKED" : "ACCEPTED", "用户同步目标",
                correlation == null ? "data-sync" : "agent-runtime", correlationEvidence,
                correlation == null ? task.getCreateTime() : correlation.getCreateTime(),
                correlation == null ? "AGENT_EXECUTION_NOT_LINKED" : null));

        nodes.add(agentNode("agent-orchestration", "DATA_SYNC_AGENT", correlation,
                correlation == null ? "NOT_APPLICABLE" : "BRIDGED", correlationEvidence,
                correlation == null ? "AGENT_EXECUTION_NOT_LINKED" : null));

        String commandEvidence = correlation == null || directEntry(correlation)
                ? null : "agent-command:" + correlation.getCommandId();
        nodes.add(node("command-dispatch", "COMMAND_DISPATCH", null,
                commandDispatchState(correlation, command),
                "Agent 命令出箱与投递", "agent-runtime", commandEvidence,
                firstNonNull(command.publishedAt(), command.updatedAt()),
                commandDispatchReason(correlation, command)));
        nodes.add(node("java-audit", "JAVA_AUDIT", null, javaAuditState(correlation, audit),
                "Java 工具审计", "agent-runtime", auditEvidence,
                firstNonNull(audit.executionFinishTime(), audit.executionStartTime(), audit.updateTime()),
                javaAuditReason(correlation, audit)));
        String rootState = rootExecution == null
                ? value(recovery.executionState(), "UNKNOWN")
                : value(rootExecution.getExecutionState(), "UNKNOWN");
        LocalDateTime rootFinishedAt = rootExecution == null
                ? recovery.executionFinishedAt() : rootExecution.getFinishedAt();
        nodes.add(node("root-worker-execution", "WORKER", null, rootState,
                Objects.equals(rootExecutionId, currentExecutionId)
                        ? "data-sync worker 执行" : "data-sync 根执行",
                "data-sync", rootWorkerEvidence, rootFinishedAt, workerReason(rootState)));
        nodes.add(node("recovery-kafka-event", "KAFKA_EVENT", null, recoveryKafkaState(recovery),
                "Recovery Kafka 触发与消费", "data-sync/agent-runtime", recoveryKafkaEvidence,
                recovery.consumerResultAt(), recoveryKafkaReason(recovery)));
        nodes.add(node("recovery", "RECOVERY", null, recoveryState(recovery),
                "无人值守自治恢复", "data-sync/agent-runtime", recoveryEvidence,
                firstNonNull(recovery.caseUpdatedAt(), recovery.consumerResultAt()), recoveryReason(recovery)));
        if (!Objects.equals(rootExecutionId, currentExecutionId)) {
            nodes.add(node("current-worker-execution", "WORKER", null,
                    value(recovery.executionState(), "UNKNOWN"),
                    "data-sync 恢复后重跑", "data-sync", currentWorkerEvidence,
                    recovery.executionFinishedAt(), workerReason(recovery.executionState())));
        }
        nodes.add(node("final-verification", "FINAL_VERIFICATION", null, finalState(recovery),
                "最终验证", "data-sync", currentWorkerEvidence,
                firstNonNull(recovery.executionFinishedAt(), recovery.caseUpdatedAt()), finalReason(recovery)));
        return nodes;
    }

    /** 把每两个相邻节点连成只读顺序边，并根据终点状态给出推进情况。 */
    private List<SyncExecutionLifecycleGraphView.LifecycleEdge> buildEdges(
            List<SyncExecutionLifecycleGraphView.LifecycleNode> nodes) {
        List<SyncExecutionLifecycleGraphView.LifecycleEdge> edges = new ArrayList<>();
        for (int index = 1; index < nodes.size(); index++) {
            SyncExecutionLifecycleGraphView.LifecycleNode from = nodes.get(index - 1);
            SyncExecutionLifecycleGraphView.LifecycleNode to = nodes.get(index);
            edges.add(new SyncExecutionLifecycleGraphView.LifecycleEdge(
                    from.nodeId(),
                    to.nodeId(),
                    "NEXT",
                    edgeState(to.state()),
                    to.evidenceId()
            ));
        }
        return edges;
    }

    /** 统一登记证据来源、状态、时间和可授权资源引用。 */
    private List<SyncExecutionLifecycleGraphView.LifecycleEvidence> buildEvidence(
            SyncTask task,
            Long rootExecutionId,
            Long currentExecutionId,
            SyncExecution rootExecution,
            SyncAgentExecutionCorrelation correlation,
            AgentRuntimeAuditObservation audit,
            AgentRuntimeCommandObservation command,
            SyncAutopilotRecoveryStatusView recovery) {
        List<SyncExecutionLifecycleGraphView.LifecycleEvidence> evidence = new ArrayList<>();
        if (correlation != null) {
            evidence.add(new SyncExecutionLifecycleGraphView.LifecycleEvidence(
                    "correlation:" + correlation.getId(), "data-sync", "AGENT_EXECUTION_CORRELATION",
                    "RECORDED", correlation.getCreateTime(), "AUTHORITATIVE",
                    "entryMode=" + value(correlation.getEntryMode(), "ASYNC_AGENT_COMMAND")
                            + ",session=" + correlation.getSessionId() + ",run=" + correlation.getRunId()));
            evidence.add(new SyncExecutionLifecycleGraphView.LifecycleEvidence(
                    "agent-audit:" + correlation.getAuditId(), "agent-runtime", "TOOL_EXECUTION_AUDIT",
                    javaAuditState(correlation, audit),
                    firstNonNull(audit.executionFinishTime(), audit.executionStartTime(), audit.updateTime()),
                    audit.available() && audit.found() ? "AUTHORITATIVE" : "UNAVAILABLE",
                    "audit=" + correlation.getAuditId()));
            if (!directEntry(correlation) && correlation.getCommandId() != null) {
                evidence.add(new SyncExecutionLifecycleGraphView.LifecycleEvidence(
                        "agent-command:" + correlation.getCommandId(), "agent-runtime", "COMMAND_OUTBOX",
                        commandDispatchState(correlation, command),
                        firstNonNull(command.publishedAt(), command.updatedAt()),
                        command.available() && command.found() ? "AUTHORITATIVE" : "UNAVAILABLE",
                        "command=" + correlation.getCommandId()));
            }
        }
        if (!Objects.equals(rootExecutionId, currentExecutionId)) {
            evidence.add(new SyncExecutionLifecycleGraphView.LifecycleEvidence(
                    "sync-execution:" + rootExecutionId, "data-sync", "ROOT_WORKER_EXECUTION",
                    rootExecution == null ? "UNAVAILABLE" : value(rootExecution.getExecutionState(), "UNKNOWN"),
                    rootExecution == null ? null : rootExecution.getFinishedAt(),
                    rootExecution == null ? "UNAVAILABLE" : "AUTHORITATIVE",
                    "task=" + task.getId() + ",execution=" + rootExecutionId));
        }
        evidence.add(new SyncExecutionLifecycleGraphView.LifecycleEvidence(
                "sync-execution:" + currentExecutionId, "data-sync", "WORKER_EXECUTION",
                value(recovery.executionState(), "UNKNOWN"), recovery.executionFinishedAt(),
                "AUTHORITATIVE",
                "task=" + task.getId() + ",execution=" + currentExecutionId));
        if (hasRecoveryKafkaFact(recovery)) {
            evidence.add(new SyncExecutionLifecycleGraphView.LifecycleEvidence(
                    "autopilot-outbox:" + rootExecutionId, "data-sync", "AUTOPILOT_RECOVERY_KAFKA",
                    recoveryKafkaState(recovery), recovery.consumerResultAt(),
                    "AUTHORITATIVE",
                    "task=" + task.getId() + ",rootExecution=" + rootExecutionId));
        }
        if (recovery.caseId() != null) {
            evidence.add(new SyncExecutionLifecycleGraphView.LifecycleEvidence(
                    "recovery-case:" + recovery.caseId(), "data-sync", "AUTOPILOT_RECOVERY_CASE",
                    recoveryState(recovery), firstNonNull(recovery.caseUpdatedAt(), recovery.caseCreatedAt()),
                    "AUTHORITATIVE",
                    "task=" + task.getId() + ",rootExecution=" + rootExecutionId));
        }
        return evidence;
    }

    /** 判断来源完整度；缺失事实用稳定原因码显式呈现。 */
    private SourceCompleteness sourceCompleteness(SyncAgentExecutionCorrelation correlation,
                                                  AgentRuntimeAuditObservation audit,
                                                  AgentRuntimeCommandObservation command) {
        if (correlation == null) {
            return new SourceCompleteness("NOT_LINKED", "AGENT_EXECUTION_NOT_LINKED");
        }
        if (!audit.available()) {
            return new SourceCompleteness("PARTIAL", value(audit.sourceStatus(), "AGENT_RUNTIME_UNAVAILABLE"));
        }
        if (!audit.found()) {
            return new SourceCompleteness("PARTIAL", "AGENT_AUDIT_NOT_FOUND");
        }
        if (directEntry(correlation)) {
            return new SourceCompleteness("COMPLETE", null);
        }
        if (!command.available()) {
            return new SourceCompleteness("PARTIAL", value(command.sourceStatus(), "AGENT_RUNTIME_COMMAND_UNAVAILABLE"));
        }
        if (!command.found()) {
            return new SourceCompleteness("PARTIAL", "AGENT_COMMAND_NOT_FOUND");
        }
        return new SourceCompleteness("COMPLETE", null);
    }

    private SyncExecutionLifecycleGraphView.LifecycleNode agentNode(
            String nodeId,
            String role,
            SyncAgentExecutionCorrelation correlation,
            String state,
            String evidenceId,
            String reasonCode) {
        return node(nodeId, "AGENT", role, state, roleTitle(role),
                "agent-runtime", evidenceId,
                correlation == null ? null : correlation.getCreateTime(), reasonCode);
    }

    /** 把各域字段收敛为统一节点，并为缺失状态设置保守的 UNKNOWN。 */
    private SyncExecutionLifecycleGraphView.LifecycleNode node(
            String nodeId,
            String nodeType,
            String role,
            String state,
            String title,
            String source,
            String evidenceId,
            LocalDateTime occurredAt,
            String reasonCode) {
        return new SyncExecutionLifecycleGraphView.LifecycleNode(
                nodeId, nodeType, role, value(state, "UNKNOWN"), title, source,
                evidenceId, occurredAt, reasonCode);
    }

    /** 把稳定 Agent 角色码转换为中文展示名称；未知角色保留原码便于排障。 */
    private String roleTitle(String role) {
        return switch (role) {
            case "MASTER_AGENT" -> "主控 Agent";
            case "KNOWLEDGE_AGENT" -> "知识检索 Agent";
            case "DATASOURCE_AGENT" -> "数据源 Agent";
            case "DATA_SYNC_AGENT" -> "数据同步 Agent";
            case "PRECHECK_AGENT" -> "预检查 Agent";
            case "RECOVERY_AGENT" -> "恢复 Agent";
            case "MONITOR_AGENT" -> "监控 Agent";
            default -> role;
        };
    }

    /** 解释初始异步命令投递状态；直接工具入口明确标记为不适用。 */
    private String commandDispatchState(SyncAgentExecutionCorrelation correlation,
                                        AgentRuntimeCommandObservation command) {
        if (correlation == null) {
            return "NOT_APPLICABLE";
        }
        if (directEntry(correlation)) {
            return "NOT_APPLICABLE";
        }
        if (!command.available()) {
            return "UNAVAILABLE";
        }
        if (!command.found()) {
            return "NOT_FOUND";
        }
        return value(command.status(), "RECORDED");
    }

    /** 为命令投递缺失或不可查询状态给出稳定原因码。 */
    private String commandDispatchReason(SyncAgentExecutionCorrelation correlation,
                                         AgentRuntimeCommandObservation command) {
        if (correlation == null) {
            return "AGENT_EXECUTION_NOT_LINKED";
        }
        if (directEntry(correlation)) {
            return "DIRECT_AGENT_TOOL_WITHOUT_INITIAL_COMMAND";
        }
        return command.available() && command.found() ? null : command.sourceStatus();
    }

    /** 只有 durable Recovery trigger outbox/consumer result 才能声明 Kafka 节点。 */
    private String recoveryKafkaState(SyncAutopilotRecoveryStatusView recovery) {
        if (!recovery.available()) {
            return "NOT_APPLICABLE";
        }
        if (!hasRecoveryKafkaFact(recovery)) {
            return "NOT_RECORDED";
        }
        if (recovery.consumerResultStatus() != null) {
            return "CONSUMED";
        }
        return value(recovery.outboxState(), "NOT_RECORDED");
    }

    /** 区分“未触发恢复”和“有恢复 case 但缺少 Kafka 持久事实”两种情况。 */
    private String recoveryKafkaReason(SyncAutopilotRecoveryStatusView recovery) {
        if (!recovery.available()) {
            return "RECOVERY_NOT_TRIGGERED";
        }
        if (!hasRecoveryKafkaFact(recovery)) {
            return "RECOVERY_KAFKA_NOT_RECORDED";
        }
        return firstNonBlank(recovery.producerDeliveryReasonCode(), recovery.consumerResultReasonCode());
    }

    /**
     * 只有 outbox 或 consumer 的持久字段存在时才承认发生过 Recovery Kafka 链路。
     *
     * <p>Recovery case 可以由迁移数据、人工补偿或未来其他通道产生，不能反向推导 Kafka 一定触发过。</p>
     */
    private boolean hasRecoveryKafkaFact(SyncAutopilotRecoveryStatusView recovery) {
        return recovery != null && (recovery.outboxState() != null
                || recovery.producerDeliveryStatus() != null
                || recovery.producerDeliveryReasonCode() != null
                || recovery.consumerResultStatus() != null
                || recovery.consumerResultReasonCode() != null
                || recovery.consumerResultAt() != null);
    }

    /** 判断关联是否来自六 Specialist 主流程的直接工具调用。 */
    private boolean directEntry(SyncAgentExecutionCorrelation correlation) {
        return correlation != null && "DIRECT_AGENT_TOOL".equalsIgnoreCase(correlation.getEntryMode());
    }

    /** 将 Agent Runtime 权威工具审计映射为统一图状态。 */
    private String javaAuditState(SyncAgentExecutionCorrelation correlation,
                                  AgentRuntimeAuditObservation audit) {
        if (correlation == null) {
            return "NOT_APPLICABLE";
        }
        if (!audit.available()) {
            return "UNAVAILABLE";
        }
        if (!audit.found()) {
            return "NOT_FOUND";
        }
        return value(audit.state(), "RECORDED");
    }

    /** 保留 Java 审计的稳定错误码，不向图中复制错误正文。 */
    private String javaAuditReason(SyncAgentExecutionCorrelation correlation,
                                   AgentRuntimeAuditObservation audit) {
        if (correlation == null) {
            return "AGENT_EXECUTION_NOT_LINKED";
        }
        if (!audit.available() || !audit.found()) {
            return audit.sourceStatus();
        }
        return audit.errorCode();
    }

    /** 优先使用 Recovery case 终态，其次使用消费者回执和生产者 outbox 状态。 */
    private String recoveryState(SyncAutopilotRecoveryStatusView recovery) {
        if (!recovery.available()) {
            return "NOT_APPLICABLE";
        }
        if (recovery.caseState() != null) {
            return recovery.caseState();
        }
        if (recovery.consumerResultStatus() != null) {
            return recovery.consumerResultStatus();
        }
        return value(recovery.outboxState(), "TRIGGERED");
    }

    /** 按人工关注、消费者、生产者的顺序选择低敏恢复原因码。 */
    private String recoveryReason(SyncAutopilotRecoveryStatusView recovery) {
        if (!recovery.available()) {
            return null;
        }
        return firstNonBlank(recovery.attentionReason(), recovery.consumerResultReasonCode(),
                recovery.producerDeliveryReasonCode());
    }

    /**
     * 计算只读最终结论。发生过 Recovery 时，worker 成功还必须同时具备 RECOVERED case 才能 VERIFIED。
     */
    private String finalState(SyncAutopilotRecoveryStatusView recovery) {
        String executionState = upper(recovery.executionState());
        String caseState = upper(recovery.caseState());
        if ("SUCCEEDED".equals(executionState)
                && (!recovery.available() || "RECOVERED".equals(caseState))) {
            return "VERIFIED";
        }
        if ("ATTENTION_REQUIRED".equals(caseState)
                || "ATTENTION_REQUIRED".equals(upper(recovery.consumerResultStatus()))) {
            return "ATTENTION_REQUIRED";
        }
        if (List.of("FAILED", "CANCELLED", "TERMINATED").contains(executionState)) {
            return "FAILED";
        }
        return "WAITING";
    }

    /** 为尚未验证或失败的最终节点选择最有解释力的稳定原因码。 */
    private String finalReason(SyncAutopilotRecoveryStatusView recovery) {
        String state = finalState(recovery);
        if ("VERIFIED".equals(state)) {
            return null;
        }
        return firstNonBlank(recovery.attentionReason(), recovery.consumerResultReasonCode(),
                recovery.producerDeliveryReasonCode(), workerReason(recovery.executionState()));
    }

    /** 把 worker 状态归为终态失败、尚未终止或无需附加原因。 */
    private String workerReason(String executionState) {
        String state = upper(executionState);
        if (List.of("FAILED", "CANCELLED", "TERMINATED").contains(state)) {
            return "WORKER_TERMINAL_FAILURE";
        }
        if (List.of("QUEUED", "RUNNING", "RETRYING", "PAUSED").contains(state)) {
            return "WORKER_NOT_TERMINAL";
        }
        return null;
    }

    /** 边状态只由终点事实决定，用于前端显示推进、等待或阻断，不驱动任何业务状态。 */
    private String edgeState(String targetState) {
        String state = upper(targetState);
        if (List.of("NOT_APPLICABLE", "NOT_LINKED").contains(state)) {
            return "NOT_APPLICABLE";
        }
        if (List.of("FAILED", "ATTENTION_REQUIRED", "BLOCKED", "UNAVAILABLE", "NOT_FOUND").contains(state)) {
            return "BLOCKED";
        }
        if (List.of("WAITING", "QUEUED", "RUNNING", "RETRYING", "PENDING", "NOT_RECORDED").contains(state)) {
            return "WAITING";
        }
        return "COMPLETED";
    }

    /** 统一清理字符串，并在来源缺值时采用显式保守默认值。 */
    private String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    /** 使用固定 Locale 归一化有限状态码，避免主机区域设置影响判断。 */
    private String upper(String value) {
        return value(value, "").toUpperCase(Locale.ROOT);
    }

    /** 返回第一个非空值，常用于选择最准确的发生时间。 */
    @SafeVarargs
    private final <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /** 返回第一个非空白稳定原因码，不拼接或暴露原始错误正文。 */
    private String firstNonBlank(String... values) {
        return java.util.Arrays.stream(values)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .findFirst()
                .orElse(null);
    }

    private record SourceCompleteness(String status, String reason) {
    }
}
