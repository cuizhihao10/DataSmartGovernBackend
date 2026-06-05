/**
 * @Author : Cui
 * @Date: 2026/06/05 00:00
 * @Description DataSmart Govern Backend - AgentSessionHandoffDagService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentSessionHandoffDagEdgeView;
import com.czh.datasmart.govern.agent.controller.dto.AgentSessionHandoffDagNodeView;
import com.czh.datasmart.govern.agent.controller.dto.AgentSessionHandoffDagQueryResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentSessionHandoffDagView;
import com.czh.datasmart.govern.agent.controller.dto.AgentSessionSchedulingProjectionQueryResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentSessionSchedulingProjectionView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Master Agent handoff DAG 只读投影服务。
 *
 * <p>本服务是多 Agent 控制面的“解释层”，输入是上一阶段已经稳定的
 * `agent_session_scheduling_recorded` 强类型投影，输出是更适合产品管理台展示的交接 DAG。
 * 它不直接调用模型、不执行工具、不审批、不写 Kafka，也不创建真实 Agent worker。</p>
 *
 * <p>为什么放在 Java agent-runtime：Java 控制面负责审计、权限收口、运行详情、回放和后续执行治理；
 * Python Runtime 更适合做意图分析、模型路由和会话调度。handoff DAG 处在“调度事实已经产生，但执行动作
 * 尚未开始”的中间层，因此由 Java 读取低敏事件并生成只读图，能让前端、审计台和审批台共享稳定契约。</p>
 *
 * <p>与前沿 Agent 协议的关系：MCP 关注工具、资源和 prompt 的上下文边界；A2A/Agent Card 关注
 * Agent 能力发现、任务状态和跨 Agent 交接；现代 Agents SDK 强调 handoff、tool call 和 tracing。
 * 本服务不急着绑定某一种协议，而是先把这些共同概念抽象成控制面 DAG：Master、Specialist、Guardrail、
 * Tool Control、Feedback、Second Turn。后续接入任一协议时，可以把协议事件映射到这些节点，而不推翻产品 UI。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentSessionHandoffDagService {

    private static final String MASTER_ROLE = "MASTER_ORCHESTRATOR";
    private static final String TOOL_CONTROL_ROLE = "TOOL_CONTROL_PLANE";
    private static final String FEEDBACK_ROLE = "FEEDBACK_SYNTHESIZER";
    private static final String SECOND_TURN_ROLE = "SECOND_TURN_REASONER";
    private static final String MEMORY_ROLE = "MEMORY_AGENT";
    private static final String PERMISSION_ROLE = "PERMISSION_AGENT";
    private static final String INDEX_SOURCE = "runtime-event-projection-fallback";

    private final AgentSessionSchedulingProjectionService schedulingProjectionService;

    /**
     * 查询 Master Agent handoff DAG。
     *
     * <p>调用方传入的 run/session/tenant/project/actor/afterSequence/limit 等条件会先交给
     * `AgentSessionSchedulingProjectionService`。该服务已经负责事件类型固定、租户与项目范围收口、
     * replaySequence 增量读取和低敏字段解析，因此 handoff DAG 不重复实现这些横切逻辑。</p>
     *
     * @param query 原始查询条件，通常来自控制器 request param。
     * @param accessContext gateway 透传并解析后的访问上下文。
     * @return 基于会话调度快照生成的 DAG 列表和窗口级统计。
     */
    public AgentSessionHandoffDagQueryResponse queryHandoffDags(
            AgentRuntimeEventProjectionQuery query,
            AgentRuntimeEventQueryAccessContext accessContext) {
        AgentSessionSchedulingProjectionQueryResponse schedulingResponse =
                schedulingProjectionService.querySnapshots(query, accessContext);
        List<AgentSessionHandoffDagView> dags = schedulingResponse.snapshots().stream()
                .map(this::buildDag)
                .toList();
        return new AgentSessionHandoffDagQueryResponse(
                schedulingResponse.limit(),
                dags.size(),
                INDEX_SOURCE,
                countState(dags, "READY"),
                countState(dags, "DEGRADED"),
                countState(dags, "APPROVAL_REQUIRED"),
                countState(dags, "BLOCKED"),
                dags.stream().filter(dag -> Boolean.TRUE.equals(dag.executable())).count(),
                dags.stream().filter(dag -> Boolean.TRUE.equals(dag.handoffRequired())).count(),
                dags
        );
    }

    private AgentSessionHandoffDagView buildDag(AgentSessionSchedulingProjectionView snapshot) {
        String dagState = normalizeStatus(snapshot.status());
        boolean executable = "READY".equals(dagState)
                && !Boolean.TRUE.equals(snapshot.handoffRequired())
                && !Boolean.TRUE.equals(snapshot.approvalRequired());
        List<AgentSessionHandoffDagNodeView> nodes = new ArrayList<>();
        List<AgentSessionHandoffDagEdgeView> edges = new ArrayList<>();

        nodes.add(masterNode(snapshot, dagState, executable));
        addMemoryNode(snapshot, dagState, executable, nodes, edges);
        addSpecialistNodes(snapshot, dagState, executable, nodes, edges);
        addApprovalNode(snapshot, dagState, executable, nodes, edges);
        addToolControlNode(snapshot, dagState, executable, nodes, edges);
        addFeedbackAndSecondTurnNodes(snapshot, dagState, executable, nodes, edges);

        return new AgentSessionHandoffDagView(
                snapshot.identityKey(),
                snapshot.runId(),
                snapshot.sessionId(),
                snapshot.replaySequence(),
                snapshot.createdAt(),
                snapshot.status(),
                dagState,
                executable,
                snapshot.handoffRequired(),
                nodes.size(),
                edges.size(),
                summaryReasons(snapshot, dagState, executable),
                recommendedActions(snapshot, dagState, executable),
                List.copyOf(nodes),
                List.copyOf(edges)
        );
    }

    private AgentSessionHandoffDagNodeView masterNode(AgentSessionSchedulingProjectionView snapshot,
                                                      String dagState,
                                                      boolean executable) {
        List<String> reasons = List.of(
                "Master Agent 是本轮会话的编排入口，负责把用户意图拆成专家 Agent、工具治理、记忆上下文和反馈阶段。",
                "该节点来自会话调度投影的 primaryAgentRole，不读取或暴露用户原始 prompt。"
        );
        return node(
                "master",
                "MASTER",
                "SESSION_INTAKE",
                defaultText(snapshot.primaryAgentRole(), MASTER_ROLE),
                dagState,
                true,
                executable || !"BLOCKED".equals(dagState),
                List.of(),
                evidenceRefs("role:" + defaultText(snapshot.primaryAgentRole(), MASTER_ROLE)),
                reasons,
                "BLOCKED".equals(dagState)
                        ? List.of("先处理调度事件中的阻塞原因，再允许 Master 继续分派。")
                        : List.of("保持 Master 节点只做编排，不直接绕过权限或工具控制面。")
        );
    }

    private void addMemoryNode(AgentSessionSchedulingProjectionView snapshot,
                               String dagState,
                               boolean executable,
                               List<AgentSessionHandoffDagNodeView> nodes,
                               List<AgentSessionHandoffDagEdgeView> edges) {
        if (snapshot.memoryDependencies() == null || snapshot.memoryDependencies().isEmpty()) {
            return;
        }
        nodes.add(node(
                "memory-context",
                "MEMORY",
                "CONTEXT_RETRIEVAL",
                MEMORY_ROLE,
                dagState,
                true,
                executable,
                List.of("master"),
                evidenceRefs(prefixAll("memory:", snapshot.memoryDependencies())),
                List.of(
                        "长期/短期记忆节点表示本轮会话需要读取历史事实或治理上下文。",
                        "DAG 只记录记忆类型，不记录记忆正文，避免审计接口泄露敏感业务内容。"
                ),
                executable
                        ? List.of("后续执行器应按租户、项目和 memoryNamespace 过滤记忆。")
                        : List.of("在降级、审批或阻塞状态下，不应自动读取更多记忆正文。")
        ));
        edges.add(edge("master", "memory-context", "MASTER_TO_MEMORY",
                "Master 先确定本轮需要哪些记忆类型，再由 Memory Agent 或记忆检索服务提供低敏上下文。"));
    }

    private void addSpecialistNodes(AgentSessionSchedulingProjectionView snapshot,
                                    String dagState,
                                    boolean executable,
                                    List<AgentSessionHandoffDagNodeView> nodes,
                                    List<AgentSessionHandoffDagEdgeView> edges) {
        for (String role : specialistRoles(snapshot)) {
            String nodeId = "specialist-" + role.toLowerCase(Locale.ROOT).replace("_agent", "").replace("_", "-");
            nodes.add(node(
                    nodeId,
                    specialistType(role),
                    "SPECIALIST_ANALYSIS",
                    role,
                    dagState,
                    true,
                    executable && !handoffRole(snapshot, role),
                    List.of("master"),
                    evidenceRefs("role:" + role),
                    List.of(
                            "专家 Agent 节点表示 Master 需要把部分业务判断交给领域角色处理。",
                            "当前节点只表达角色参与事实，不代表已经启动并发 Agent 执行。"
                    ),
                    handoffRole(snapshot, role)
                            ? List.of("该角色被标记为 handoff，需要等待审批、人工接管或更强权限后再进入执行。")
                            : List.of("如果未来接入真实多 Agent worker，可把该节点映射为专家 Agent 任务。")
            ));
            edges.add(edge("master", nodeId, "MASTER_TO_SPECIALIST",
                    "Master 将会话意图分派给领域专家 Agent，但真实执行仍需经过权限和工具治理。"));
        }
    }

    private void addApprovalNode(AgentSessionSchedulingProjectionView snapshot,
                                 String dagState,
                                 boolean executable,
                                 List<AgentSessionHandoffDagNodeView> nodes,
                                 List<AgentSessionHandoffDagEdgeView> edges) {
        if (!Boolean.TRUE.equals(snapshot.approvalRequired()) && !Boolean.TRUE.equals(snapshot.handoffRequired())) {
            return;
        }
        nodes.add(node(
                "approval-handoff",
                "GUARDRAIL",
                "APPROVAL_AND_HANDOFF",
                handoffAgentRole(snapshot),
                dagState,
                true,
                false,
                List.of("master"),
                evidenceRefs(prefixAll("handoffRole:", snapshot.handoffAgentRoles())),
                List.of(
                        "审批/接管节点用于承载高风险工具、跨权限操作、租户边界变化或人工复核。",
                        "第一版 DAG 在需要审批或 handoff 时一律不自动执行，避免越权调用工具。"
                ),
                List.of("由 permission-admin 或人工审核台确认后，再允许后续工具控制节点进入执行候选。")
        ));
        edges.add(edge("master", "approval-handoff", "MASTER_TO_GUARDRAIL",
                "Master 发现本轮存在审批或 handoff 风险，因此必须先经过防护节点。"));
    }

    private void addToolControlNode(AgentSessionSchedulingProjectionView snapshot,
                                    String dagState,
                                    boolean executable,
                                    List<AgentSessionHandoffDagNodeView> nodes,
                                    List<AgentSessionHandoffDagEdgeView> edges) {
        if (snapshot.plannedToolNames() == null || snapshot.plannedToolNames().isEmpty()) {
            return;
        }
        List<String> blockers = Boolean.TRUE.equals(snapshot.approvalRequired()) || Boolean.TRUE.equals(snapshot.handoffRequired())
                ? List.of("approval-handoff")
                : List.of("master");
        nodes.add(node(
                "tool-control",
                "TOOL_CONTROL",
                "TOOL_GOVERNANCE",
                TOOL_CONTROL_ROLE,
                dagState,
                true,
                executable,
                blockers,
                evidenceRefs(prefixAll("tool:", snapshot.plannedToolNames())),
                List.of(
                        "工具控制节点统一承接工具预算、参数校验、幂等、审计、超时和执行副作用治理。",
                        "这能防止专家 Agent 直接越过 Java 控制面调用真实业务工具。"
                ),
                executable
                        ? List.of("进入真实执行前仍需校验 tool budget、权限、限流、幂等键和下游健康状态。")
                        : List.of("当前 DAG 尚不可执行，工具控制节点只作为审计和可视化解释。")
        ));
        if (Boolean.TRUE.equals(snapshot.approvalRequired()) || Boolean.TRUE.equals(snapshot.handoffRequired())) {
            edges.add(edge("approval-handoff", "tool-control", "GUARDRAIL_TO_TOOL_CONTROL",
                    "审批或接管完成后，才允许进入工具控制面。"));
        } else {
            edges.add(edge("master", "tool-control", "MASTER_TO_TOOL_CONTROL",
                    "没有审批阻塞时，Master 可以把已规划工具交给 Java 工具控制面预检。"));
        }
        for (String specialistNodeId : specialistNodeIds(snapshot)) {
            edges.add(edge(specialistNodeId, "tool-control", "SPECIALIST_TO_TOOL_CONTROL",
                    "专家 Agent 的领域判断需要汇入统一工具控制面，而不是直接执行副作用工具。"));
        }
    }

    private void addFeedbackAndSecondTurnNodes(AgentSessionSchedulingProjectionView snapshot,
                                               String dagState,
                                               boolean executable,
                                               List<AgentSessionHandoffDagNodeView> nodes,
                                               List<AgentSessionHandoffDagEdgeView> edges) {
        String feedbackBlocker = snapshot.plannedToolNames() == null || snapshot.plannedToolNames().isEmpty()
                ? "master"
                : "tool-control";
        nodes.add(node(
                "feedback",
                "FEEDBACK",
                "RESULT_FEEDBACK",
                FEEDBACK_ROLE,
                dagState,
                true,
                executable,
                List.of(feedbackBlocker),
                evidenceRefs("summary:" + defaultText(snapshot.displaySummary(), "agent-session-scheduling")),
                List.of(
                        "反馈节点负责把专家判断、工具控制结果和防护结论汇总成用户可理解的下一步。",
                        "当前只读 DAG 不返回模型输出，只说明反馈阶段存在。"
                ),
                List.of("真实接入时应把反馈内容写入 runtime event，支持 WebSocket 回放和审计导出。")
        ));
        nodes.add(node(
                "second-turn",
                "SECOND_TURN",
                "SECOND_TURN_REASONING",
                SECOND_TURN_ROLE,
                dagState,
                false,
                executable,
                List.of("feedback"),
                evidenceRefs(prefixAll("skill:", snapshot.selectedSkillCodes())),
                List.of(
                        "二轮推理节点用于表达工具或专家结果回填后的再规划能力。",
                        "将二轮推理单独成节点，可以为后续反思、纠错、重试和计划恢复预留位置。"
                ),
                List.of("后续可把该节点扩展为 LangGraph/OpenClaw 状态流转，而不是在当前服务中硬编码执行。")
        ));
        edges.add(edge(feedbackBlocker, "feedback", "CONTROL_TO_FEEDBACK",
                "工具或 Master 阶段结束后，需要进入统一反馈阶段，避免多个 Agent 分散向用户输出。"));
        edges.add(edge("feedback", "second-turn", "FEEDBACK_TO_SECOND_TURN",
                "反馈结果可触发二轮推理、计划修正或新的审批请求。"));
    }

    private AgentSessionHandoffDagNodeView node(String nodeId,
                                                String nodeType,
                                                String stage,
                                                String agentRole,
                                                String status,
                                                boolean required,
                                                boolean executable,
                                                List<String> blockedByNodeIds,
                                                List<String> evidenceRefs,
                                                List<String> reasons,
                                                List<String> recommendedActions) {
        return new AgentSessionHandoffDagNodeView(
                nodeId,
                nodeType,
                stage,
                agentRole,
                status,
                required,
                executable,
                List.copyOf(blockedByNodeIds),
                List.copyOf(evidenceRefs),
                List.copyOf(reasons),
                List.copyOf(recommendedActions)
        );
    }

    private AgentSessionHandoffDagEdgeView edge(String fromNodeId, String toNodeId, String edgeType, String reason) {
        return new AgentSessionHandoffDagEdgeView(fromNodeId, toNodeId, edgeType, reason);
    }

    private List<String> specialistRoles(AgentSessionSchedulingProjectionView snapshot) {
        Set<String> roles = new LinkedHashSet<>();
        if (snapshot.participatingAgentRoles() != null) {
            for (String role : snapshot.participatingAgentRoles()) {
                String normalized = defaultText(role, "").toUpperCase(Locale.ROOT);
                if (!normalized.isBlank()
                        && !MASTER_ROLE.equals(normalized)
                        && !MEMORY_ROLE.equals(normalized)) {
                    roles.add(normalized);
                }
            }
        }
        if (roles.isEmpty()) {
            roles.add("KNOWLEDGE_AGENT");
        }
        return List.copyOf(roles);
    }

    private List<String> specialistNodeIds(AgentSessionSchedulingProjectionView snapshot) {
        return specialistRoles(snapshot).stream()
                .map(role -> "specialist-" + role.toLowerCase(Locale.ROOT).replace("_agent", "").replace("_", "-"))
                .toList();
    }

    private String specialistType(String role) {
        if (PERMISSION_ROLE.equals(role) || role.contains("OPS")) {
            return "GUARDRAIL";
        }
        return "SPECIALIST";
    }

    private boolean handoffRole(AgentSessionSchedulingProjectionView snapshot, String role) {
        return snapshot.handoffAgentRoles() != null && snapshot.handoffAgentRoles().stream()
                .map(value -> defaultText(value, "").toUpperCase(Locale.ROOT))
                .anyMatch(role::equals);
    }

    private String handoffAgentRole(AgentSessionSchedulingProjectionView snapshot) {
        if (snapshot.handoffAgentRoles() == null || snapshot.handoffAgentRoles().isEmpty()) {
            return PERMISSION_ROLE;
        }
        return String.join(",", snapshot.handoffAgentRoles());
    }

    private List<String> summaryReasons(AgentSessionSchedulingProjectionView snapshot,
                                        String dagState,
                                        boolean executable) {
        List<String> reasons = new ArrayList<>();
        reasons.add("该 DAG 来源于 agent_session_scheduling_recorded 投影，是低敏控制面解释，不代表真实 Agent 已经执行。");
        reasons.add("当前调度状态为 " + dagState + "，因此 DAG executable=" + executable + "。");
        if (Boolean.TRUE.equals(snapshot.handoffRequired())) {
            reasons.add("调度事件标记 handoffRequired=true，说明至少存在需要审批、人工接管或更强权限的协作阶段。");
        }
        if (snapshot.plannedToolNames() != null && !snapshot.plannedToolNames().isEmpty()) {
            reasons.add("本轮存在 plannedToolNames，DAG 会加入 Tool Control Plane，确保工具副作用由 Java 控制面治理。");
        }
        if (snapshot.memoryDependencies() != null && !snapshot.memoryDependencies().isEmpty()) {
            reasons.add("本轮存在 memoryDependencies，DAG 会加入 Memory 节点，但不会暴露长期记忆正文。");
        }
        return List.copyOf(reasons);
    }

    private List<String> recommendedActions(AgentSessionSchedulingProjectionView snapshot,
                                            String dagState,
                                            boolean executable) {
        List<String> actions = new ArrayList<>();
        if ("BLOCKED".equals(dagState)) {
            actions.add("优先排查模型网关、Skill admission、tool budget 或审批前置条件，解除阻塞后再生成执行候选。");
        } else if ("DEGRADED".equals(dagState)) {
            actions.add("将该 DAG 保持为不可执行，只用于展示和诊断；确认降级来源后再允许真实 worker 消费。");
        } else if ("APPROVAL_REQUIRED".equals(dagState) || Boolean.TRUE.equals(snapshot.approvalRequired())) {
            actions.add("把 approval-handoff 节点推送到 permission-admin 或人工审核台，审核通过后再进入工具控制面。");
        } else if (executable) {
            actions.add("可以进入后续 dry-run 或真实执行候选，但仍必须经过权限、限流、幂等和下游健康检查。");
        }
        actions.add("后续若接入 MCP/A2A/LangGraph，可优先把协议事件映射到本 DAG 节点，而不是重做前端展示契约。");
        return List.copyOf(actions);
    }

    private long countState(List<AgentSessionHandoffDagView> dags, String state) {
        return dags.stream()
                .filter(dag -> state.equalsIgnoreCase(Objects.toString(dag.dagExecutionState(), "")))
                .count();
    }

    private String normalizeStatus(String status) {
        String normalized = defaultText(status, "UNKNOWN").toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "READY", "DEGRADED", "APPROVAL_REQUIRED", "BLOCKED" -> normalized;
            default -> "DEGRADED";
        };
    }

    private List<String> evidenceRefs(String value) {
        return value == null || value.isBlank() ? List.of() : List.of(value);
    }

    private List<String> evidenceRefs(List<String> values) {
        return values == null ? List.of() : values.stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
    }

    private List<String> prefixAll(String prefix, List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> prefix + value.trim())
                .distinct()
                .toList();
    }

    private String defaultText(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }
}
