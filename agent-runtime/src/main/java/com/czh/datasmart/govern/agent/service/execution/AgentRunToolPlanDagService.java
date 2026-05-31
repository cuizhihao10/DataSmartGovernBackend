/**
 * @Author : Cui
 * @Date: 2026/05/31 22:25
 * @Description DataSmart Govern Backend - AgentRunToolPlanDagService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.execution;

import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolExecutionPolicyItemView;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolExecutionPolicyView;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolPlanDagView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionAuditView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolPlanDagEdgeView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolPlanDagNodeView;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionState;
import com.czh.datasmart.govern.agent.service.AgentToolExecutionAuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Agent Run 级 ToolPlan DAG 预检服务。
 *
 * <p>该服务只读取工具审计和执行策略，不执行、不审批、不投递 Kafka。它把现有线性 ToolPlan 升级为
 * “可解释的图”：节点、依赖边、并行组、失败策略、结果别名、拓扑顺序、ready 节点和阻断原因。</p>
 * <p>先做预检而不是直接做执行 DAG，是因为 Agent 工具调用有真实副作用；Python Runtime、前端和 Java worker
 * 也需要先共享同一套图解释语义。当前 DAG 复用 auditId/state/policy，不创建第二套工具状态机。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentRunToolPlanDagService {
    private static final String DEFAULT_PARALLEL_GROUP = "DEFAULT";
    private static final String UNKNOWN_DECISION = "UNKNOWN";
    private final AgentRunToolExecutionPolicyService policyService;
    private final AgentToolExecutionAuditService auditService;

    /**
     * 查询某次 Run 的 ToolPlan DAG 预检。
     * <p>会复用 `policyService.inspectRunPolicy(...)` 的 runtime/session/run 校验和工具策略判断。
     * DAG 只负责“图依赖是否满足”，不重新发明审批、参数缺失、终态阻断等策略。</p>
     *
     * @param sessionId Agent 会话 ID
     * @param runId Agent Run ID
     */
    public AgentRunToolPlanDagView inspectRunToolPlanDag(String sessionId, String runId) {
        AgentRunToolExecutionPolicyView policy = policyService.inspectRunPolicy(sessionId, runId);
        Map<String, AgentRunToolExecutionPolicyItemView> policyByAuditId = indexPolicyItems(policy);
        List<AgentToolExecutionAuditView> audits = auditService.listByRun(sessionId, runId);
        List<NodeDraft> nodeDrafts = buildNodeDrafts(audits, policyByAuditId);
        AgentToolPlanDagDependencyMode dependencyMode = resolveDependencyMode(nodeDrafts);
        List<AgentToolPlanDagEdgeView> edges = buildEdges(nodeDrafts, dependencyMode);
        GraphShape graph = buildGraphShape(nodeDrafts, edges);
        List<AgentToolPlanDagNodeView> nodes = buildNodeViews(nodeDrafts, graph);
        List<String> readyNodeIds = nodes.stream()
                .filter(node -> Boolean.TRUE.equals(node.readyForExecution()))
                .map(AgentToolPlanDagNodeView::nodeId)
                .toList();
        List<String> blockedNodeIds = nodes.stream()
                .filter(node -> !Boolean.TRUE.equals(node.readyForExecution()))
                .map(AgentToolPlanDagNodeView::nodeId)
                .toList();
        return new AgentRunToolPlanDagView(
                sessionId,
                runId,
                dependencyMode.name(),
                nodes.size(),
                edges.size(),
                readyNodeIds.size(),
                blockedNodeIds.size(),
                graph.hasCycle(),
                graph.cycleNodeIds(),
                graph.topologicalNodeIds(),
                readyNodeIds,
                blockedNodeIds,
                buildSummaryReasons(nodes, edges, graph, dependencyMode),
                buildRecommendedActions(nodes, graph),
                nodes,
                edges
        );
    }
    private List<NodeDraft> buildNodeDrafts(List<AgentToolExecutionAuditView> audits,
                                            Map<String, AgentRunToolExecutionPolicyItemView> policyByAuditId) {
        List<NodeDraft> nodes = new ArrayList<>();
        Set<String> usedNodeIds = new LinkedHashSet<>();
        for (int index = 0; index < audits.size(); index++) {
            AgentToolExecutionAuditView audit = audits.get(index);
            int sequence = resolveSequence(audit, index + 1);
            String rawNodeId = firstText(audit.governanceHints(), "planNodeId", "nodeId", "toolPlanNodeId");
            String nodeId = rawNodeId == null ? "node-" + sequence : rawNodeId;
            List<String> reasons = new ArrayList<>();
            if (usedNodeIds.contains(nodeId)) {
                reasons.add("DAG 节点 ID 重复，Java 控制面已追加序号形成唯一节点 ID；请让 Python Runtime 提供唯一 planNodeId。");
                nodeId = nodeId + "-" + sequence;
            }
            usedNodeIds.add(nodeId);
            nodes.add(new NodeDraft(
                    nodeId,
                    sequence,
                    audit,
                    policyByAuditId.get(audit.auditId()),
                    dependencyRefs(audit),
                    normalizeText(firstText(audit.governanceHints(), "parallelGroup", "parallel_group"), DEFAULT_PARALLEL_GROUP),
                    failurePolicy(audit),
                    normalizeText(firstText(audit.governanceHints(), "resultAlias", "outputAlias", "resultKey"), audit.auditId()),
                    reasons
            ));
        }
        return nodes.stream()
                .sorted(Comparator.comparing(NodeDraft::sequence))
                .toList();
    }

    private AgentToolPlanDagDependencyMode resolveDependencyMode(List<NodeDraft> nodes) {
        boolean hasExplicitDependency = nodes.stream().anyMatch(node -> !node.dependencyRefs().isEmpty());
        return hasExplicitDependency ? AgentToolPlanDagDependencyMode.EXPLICIT : AgentToolPlanDagDependencyMode.LEGACY_SEQUENCE;
    }

    private List<AgentToolPlanDagEdgeView> buildEdges(List<NodeDraft> nodes,
                                                      AgentToolPlanDagDependencyMode dependencyMode) {
        if (dependencyMode == AgentToolPlanDagDependencyMode.LEGACY_SEQUENCE) {
            List<AgentToolPlanDagEdgeView> edges = new ArrayList<>();
            for (int index = 1; index < nodes.size(); index++) {
                edges.add(new AgentToolPlanDagEdgeView(
                        nodes.get(index - 1).nodeId(),
                        nodes.get(index).nodeId(),
                        "LEGACY_SEQUENCE",
                        "当前 ToolPlan 未提供显式 dependsOn，Java 控制面按历史线性顺序保守生成串行依赖。"
                ));
            }
            return edges;
        }
        return explicitEdges(nodes);
    }

    private List<AgentToolPlanDagEdgeView> explicitEdges(List<NodeDraft> nodes) {
        Map<String, List<NodeDraft>> refs = buildReferenceIndex(nodes);
        List<AgentToolPlanDagEdgeView> edges = new ArrayList<>();
        for (NodeDraft node : nodes) {
            for (String dependencyRef : node.dependencyRefs()) {
                NodeDraft dependency = resolveDependency(refs, dependencyRef);
                if (dependency == null) {
                    node.reasons().add("依赖引用无法解析或不唯一，dependencyRef=" + dependencyRef + "。");
                    continue;
                }
                edges.add(new AgentToolPlanDagEdgeView(
                        dependency.nodeId(),
                        node.nodeId(),
                        "EXPLICIT_DEPENDENCY",
                        "由 governanceHints.dependsOn/dependencies/after 显式声明。"
                ));
            }
        }
        return edges;
    }

    private GraphShape buildGraphShape(List<NodeDraft> nodes, List<AgentToolPlanDagEdgeView> edges) {
        Map<String, Integer> indegree = new LinkedHashMap<>();
        Map<String, List<String>> outgoing = new LinkedHashMap<>();
        Map<String, List<String>> incoming = new LinkedHashMap<>();
        nodes.forEach(node -> {
            indegree.put(node.nodeId(), 0);
            outgoing.put(node.nodeId(), new ArrayList<>());
            incoming.put(node.nodeId(), new ArrayList<>());
        });
        for (AgentToolPlanDagEdgeView edge : edges) {
            if (!indegree.containsKey(edge.fromNodeId()) || !indegree.containsKey(edge.toNodeId())) {
                continue;
            }
            outgoing.get(edge.fromNodeId()).add(edge.toNodeId());
            incoming.get(edge.toNodeId()).add(edge.fromNodeId());
            indegree.put(edge.toNodeId(), indegree.get(edge.toNodeId()) + 1);
        }
        ArrayDeque<String> queue = new ArrayDeque<>();
        indegree.forEach((nodeId, degree) -> {
            if (degree == 0) {
                queue.add(nodeId);
            }
        });
        List<String> topological = new ArrayList<>();
        while (!queue.isEmpty()) {
            String nodeId = queue.removeFirst();
            topological.add(nodeId);
            for (String next : outgoing.getOrDefault(nodeId, List.of())) {
                int nextDegree = indegree.get(next) - 1;
                indegree.put(next, nextDegree);
                if (nextDegree == 0) {
                    queue.add(next);
                }
            }
        }
        Set<String> sorted = new LinkedHashSet<>(topological);
        List<String> cycleNodes = nodes.stream()
                .map(NodeDraft::nodeId)
                .filter(nodeId -> !sorted.contains(nodeId))
                .toList();
        return new GraphShape(
                incoming,
                outgoing,
                topological,
                !cycleNodes.isEmpty(),
                cycleNodes
        );
    }

    private List<AgentToolPlanDagNodeView> buildNodeViews(List<NodeDraft> drafts, GraphShape graph) {
        Map<String, NodeDraft> nodeById = new LinkedHashMap<>();
        drafts.forEach(node -> nodeById.put(node.nodeId(), node));
        return drafts.stream()
                .map(node -> toNodeView(node, nodeById, graph))
                .toList();
    }

    private AgentToolPlanDagNodeView toNodeView(NodeDraft node,
                                                Map<String, NodeDraft> nodeById,
                                                GraphShape graph) {
        List<String> incoming = graph.incoming().getOrDefault(node.nodeId(), List.of());
        List<String> outgoing = graph.outgoing().getOrDefault(node.nodeId(), List.of());
        List<String> blockedBy = blockedBy(node, incoming, nodeById, graph);
        List<String> reasons = new ArrayList<>(node.reasons());
        List<String> actions = new ArrayList<>();
        boolean dependencySatisfied = blockedBy.isEmpty();
        boolean policyReady = isPolicyReady(node.policyItem());
        if (dependencySatisfied) {
            reasons.add("所有前置依赖均已满足，或当前节点没有前置依赖。");
        } else {
            reasons.add("仍存在未满足前置依赖，不能进入执行候选。");
            actions.add("优先处理 blockedByNodeIds 中的前置节点，再重新查询 DAG 预检。");
        }
        if (!policyReady) {
            reasons.add("当前节点的 execution-policy 尚未允许进入执行候选，decision=" + decision(node.policyItem()) + "。");
            actions.add("按 execution-policy 推荐动作处理审批、参数补齐、异步执行器或失败复核。");
        }
        if (graph.cycleNodeIds().contains(node.nodeId())) {
            reasons.add("当前节点参与依赖环，DAG 调度器无法给出安全执行顺序。");
            actions.add("修正 dependsOn，确保工具计划是有向无环图。");
        }
        boolean ready = dependencySatisfied && policyReady && !graph.cycleNodeIds().contains(node.nodeId());
        if (ready) {
            actions.add("该节点可进入后续执行器候选；真实执行仍需经过权限、限流、幂等和下游健康校验。");
        }
        return new AgentToolPlanDagNodeView(
                node.nodeId(),
                node.audit().auditId(),
                node.sequence(),
                node.audit().toolCode(),
                node.audit().state(),
                node.audit().executionMode(),
                decision(node.policyItem()),
                node.parallelGroup(),
                node.failurePolicy().name(),
                node.resultAlias(),
                incoming,
                outgoing,
                dependencySatisfied,
                ready,
                blockedBy,
                List.copyOf(reasons),
                List.copyOf(actions)
        );
    }

    private List<String> blockedBy(NodeDraft node,
                                   List<String> incoming,
                                   Map<String, NodeDraft> nodeById,
                                   GraphShape graph) {
        List<String> blockedBy = new ArrayList<>();
        for (String dependencyId : incoming) {
            NodeDraft dependency = nodeById.get(dependencyId);
            if (dependency == null || !dependencySatisfied(dependency)) {
                blockedBy.add(dependencyId);
            }
        }
        for (String dependencyRef : node.dependencyRefs()) {
            boolean resolved = incoming.stream().anyMatch(nodeId -> referenceMatches(nodeById.get(nodeId), dependencyRef));
            if (!resolved) {
                blockedBy.add("UNRESOLVED:" + dependencyRef);
            }
        }
        if (graph.cycleNodeIds().contains(node.nodeId())) {
            blockedBy.add("CYCLE:" + node.nodeId());
        }
        return blockedBy.stream().distinct().toList();
    }

    private boolean dependencySatisfied(NodeDraft dependency) {
        AgentToolExecutionState state = parseState(dependency.audit().state());
        if (state == AgentToolExecutionState.SUCCEEDED) {
            return true;
        }
        return state == AgentToolExecutionState.FAILED
                && dependency.failurePolicy() == AgentToolPlanDagFailurePolicy.CONTINUE_ON_FAILURE;
    }

    private boolean isPolicyReady(AgentRunToolExecutionPolicyItemView policyItem) {
        if (policyItem == null) {
            return false;
        }
        String decision = policyItem.decision();
        return AgentRunToolExecutionDecision.AUTO_EXECUTABLE.name().equals(decision)
                || AgentRunToolExecutionDecision.WAITING_ASYNC_EXECUTOR.name().equals(decision);
    }

    private List<String> buildSummaryReasons(List<AgentToolPlanDagNodeView> nodes,
                                             List<AgentToolPlanDagEdgeView> edges,
                                             GraphShape graph,
                                             AgentToolPlanDagDependencyMode dependencyMode) {
        List<String> reasons = new ArrayList<>();
        if (nodes.isEmpty()) {
            reasons.add("当前 Run 没有工具审计节点，可能尚未完成 AgentPlan 接入或本轮不需要调用工具。");
        }
        reasons.add("当前依赖解析模式为 " + dependencyMode.name() + "，依赖边数量为 " + edges.size() + "。");
        if (dependencyMode == AgentToolPlanDagDependencyMode.LEGACY_SEQUENCE) {
            reasons.add("未检测到显式 dependsOn，已按旧线性列表保守生成串行 DAG。");
        }
        if (graph.hasCycle()) {
            reasons.add("检测到依赖环，必须修正 ToolPlan DAG 后才能进入自动调度。");
        }
        long ready = nodes.stream().filter(node -> Boolean.TRUE.equals(node.readyForExecution())).count();
        if (ready > 0) {
            reasons.add("存在 " + ready + " 个节点满足依赖和策略条件，可作为后续执行器候选。");
        }
        return reasons;
    }

    private List<String> buildRecommendedActions(List<AgentToolPlanDagNodeView> nodes, GraphShape graph) {
        List<String> actions = new ArrayList<>();
        if (graph.hasCycle()) {
            actions.add("先修正 dependsOn 依赖环，避免 Agent 调度器进入死锁或无限等待。");
        }
        boolean hasBlocked = nodes.stream().anyMatch(node -> !Boolean.TRUE.equals(node.readyForExecution()));
        if (hasBlocked) {
            actions.add("优先处理 blockedByNodeIds、审批、参数缺失和失败复核，再重新查询 DAG。");
        }
        boolean hasReady = nodes.stream().anyMatch(node -> Boolean.TRUE.equals(node.readyForExecution()));
        if (hasReady) {
            actions.add("后续执行器可以按 parallelGroup、executionMode、限流和租户配额调度 ready 节点。");
        }
        actions.add("结果回填应按 topologicalNodeIds 顺序收集，避免后置节点在前置结果缺失时误导模型。");
        return actions;
    }

    private Map<String, AgentRunToolExecutionPolicyItemView> indexPolicyItems(AgentRunToolExecutionPolicyView policy) {
        Map<String, AgentRunToolExecutionPolicyItemView> indexed = new LinkedHashMap<>();
        for (AgentRunToolExecutionPolicyItemView item : policy.items()) {
            indexed.put(item.auditId(), item);
        }
        return indexed;
    }

    private Map<String, List<NodeDraft>> buildReferenceIndex(List<NodeDraft> nodes) {
        Map<String, List<NodeDraft>> refs = new LinkedHashMap<>();
        for (NodeDraft node : nodes) {
            addRef(refs, node.nodeId(), node);
            addRef(refs, node.audit().auditId(), node);
            addRef(refs, String.valueOf(node.sequence()), node);
        }
        return refs;
    }

    private void addRef(Map<String, List<NodeDraft>> refs, String ref, NodeDraft node) {
        if (ref == null || ref.isBlank()) {
            return;
        }
        refs.computeIfAbsent(ref.trim(), ignored -> new ArrayList<>()).add(node);
    }

    private NodeDraft resolveDependency(Map<String, List<NodeDraft>> refs, String dependencyRef) {
        List<NodeDraft> candidates = refs.get(dependencyRef);
        return candidates == null || candidates.size() != 1 ? null : candidates.getFirst();
    }

    private boolean referenceMatches(NodeDraft node, String ref) {
        return node != null && (Objects.equals(node.nodeId(), ref)
                || Objects.equals(node.audit().auditId(), ref)
                || Objects.equals(String.valueOf(node.sequence()), ref));
    }

    private List<String> dependencyRefs(AgentToolExecutionAuditView audit) {
        Map<String, Object> hints = audit.governanceHints();
        Object raw = firstValue(hints, "dependsOn", "dependsOnNodeIds", "dependencies", "after", "afterNodeIds");
        return toStringList(raw);
    }

    private AgentToolPlanDagFailurePolicy failurePolicy(AgentToolExecutionAuditView audit) {
        String raw = firstText(audit.governanceHints(), "failurePolicy", "onFailure");
        if (raw == null || raw.isBlank()) {
            return AgentToolPlanDagFailurePolicy.BLOCK_RUN;
        }
        try {
            return AgentToolPlanDagFailurePolicy.valueOf(raw.trim().replace('-', '_').toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return AgentToolPlanDagFailurePolicy.BLOCK_RUN;
        }
    }

    private List<String> toStringList(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof Collection<?> collection) {
            return collection.stream()
                    .map(String::valueOf)
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .distinct()
                    .toList();
        }
        String text = String.valueOf(raw).trim();
        if (text.isBlank()) {
            return List.of();
        }
        return Arrays.stream(text.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    private int resolveSequence(AgentToolExecutionAuditView audit, int fallback) {
        String bindingId = audit.bindingId();
        if (bindingId != null && bindingId.startsWith("plan:")) {
            String[] parts = bindingId.split(":");
            if (parts.length >= 3) {
                try {
                    return Integer.parseInt(parts[2]);
                } catch (NumberFormatException ignored) {
                    return fallback;
                }
            }
        }
        return fallback;
    }

    private String decision(AgentRunToolExecutionPolicyItemView policyItem) {
        return policyItem == null ? UNKNOWN_DECISION : policyItem.decision();
    }

    private AgentToolExecutionState parseState(String state) {
        try {
            return AgentToolExecutionState.valueOf(state == null ? "" : state.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private String firstText(Map<String, Object> values, String... keys) {
        Object value = firstValue(values, keys);
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return String.valueOf(value).trim();
    }

    private Object firstValue(Map<String, Object> values, String... keys) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        for (String key : keys) {
            if (values.containsKey(key)) {
                return values.get(key);
            }
        }
        return null;
    }

    private String normalizeText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private record NodeDraft(String nodeId,
                             int sequence,
                             AgentToolExecutionAuditView audit,
                             AgentRunToolExecutionPolicyItemView policyItem,
                             List<String> dependencyRefs,
                             String parallelGroup,
                             AgentToolPlanDagFailurePolicy failurePolicy,
                             String resultAlias,
                             List<String> reasons) {
    }

    private record GraphShape(Map<String, List<String>> incoming,
                              Map<String, List<String>> outgoing,
                              List<String> topologicalNodeIds,
                              boolean hasCycle,
                              List<String> cycleNodeIds) {
    }
}
