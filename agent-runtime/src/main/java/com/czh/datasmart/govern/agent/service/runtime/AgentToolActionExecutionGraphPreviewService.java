/**
 * @Author : Cui
 * @Date: 2026/06/07 14:27
 * @Description DataSmart Govern Backend - AgentToolActionExecutionGraphPreviewService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionExecutionGraphNodeView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionExecutionGraphQueryResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionExecutionGraphView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionIntakeDurableActionContractQueryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 工具动作执行图预览服务。
 *
 * <p>本服务承接 durable action contract preview，但职责不是继续增加 contract 字段，而是把“执行前需要经过哪些关卡”
 * 变成一张显式条件图。它对应 Codex/Claude Code、LangGraph/OpenClaw 风格 Agent Host 的关键工程思想：
 * 模型或外部协议提出工具动作后，宿主平台必须先经过入口接收、准备度判断、人工确认、持久化命令、worker 回执和结果脱敏，
 * 不能把一次 `tools/call` 或 `tool_call` 直接当作真实副作用。</p>
 *
 * <p>职责拆分说明：本类只做查询、访问范围透传和窗口级聚合；单条 contract 如何转换成节点与边，由
 * `AgentToolActionExecutionGraphPreviewBuilder` 负责。这样可以避免 service 变成超大文件，也便于后续把 builder
 * 替换成真正的执行图编排器。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentToolActionExecutionGraphPreviewService {

    private final AgentToolActionIntakeDurableActionContractService contractService;
    private final AgentToolActionExecutionGraphPreviewBuilder graphBuilder =
            new AgentToolActionExecutionGraphPreviewBuilder();

    /**
     * 查询工具动作执行图预览。
     *
     * @param query 与 runtime event projection 相同的查询条件，支持 tenant/project/actor/run/session/afterSequence。
     * @param accessContext 访问上下文，继续沿用投影服务的数据范围收口能力。
     * @return 窗口级聚合和执行图列表。
     */
    public AgentToolActionExecutionGraphQueryResponse queryExecutionGraphs(
            AgentRuntimeEventProjectionQuery query,
            AgentRuntimeEventQueryAccessContext accessContext) {
        AgentToolActionIntakeDurableActionContractQueryResponse contractResponse =
                contractService.queryContracts(query, accessContext);
        List<AgentToolActionExecutionGraphView> graphs = contractResponse.contracts().stream()
                .map(graphBuilder::buildGraph)
                .toList();
        return new AgentToolActionExecutionGraphQueryResponse(
                contractResponse.limit(),
                contractResponse.totalContracts(),
                graphs.size(),
                countState(graphs, AgentToolActionExecutionGraphPreviewBuilder.GRAPH_READY_OUTBOX),
                countState(graphs, AgentToolActionExecutionGraphPreviewBuilder.STATE_WAITING_APPROVAL),
                countState(graphs, AgentToolActionExecutionGraphPreviewBuilder.STATE_NEEDS_CLARIFICATION),
                countState(graphs, AgentToolActionExecutionGraphPreviewBuilder.STATE_WAITING_BUDGET),
                graphs.stream().filter(graph -> graphBuilder.isBlockedGraphState(graph.graphState())).count(),
                countBy(graphs, AgentToolActionExecutionGraphView::graphState),
                countNodeTypes(graphs),
                countMissingRequirements(graphs),
                summaryReasons(contractResponse.totalContracts(), graphs),
                recommendedActions(graphs),
                graphs
        );
    }

    private List<String> summaryReasons(int sourceContractCount, List<AgentToolActionExecutionGraphView> graphs) {
        java.util.ArrayList<String> reasons = new java.util.ArrayList<>();
        reasons.add("本次从 " + sourceContractCount + " 条 durable action contract 推导出 "
                + graphs.size() + " 张工具动作执行图。");
        reasons.add("执行图只表达控制面条件分支，不会写 outbox、创建审批或调用 worker。");
        if (graphs.stream().anyMatch(graph ->
                AgentToolActionExecutionGraphPreviewBuilder.GRAPH_WAITING_EVIDENCE.equals(graph.graphState()))) {
            reasons.add("窗口内存在 readiness 接近通过但仍缺可恢复执行证据的动作，这是后续 command builder 的重点输入。");
        }
        if (graphs.stream().anyMatch(graph ->
                AgentToolActionExecutionGraphPreviewBuilder.STATE_WAITING_APPROVAL.equals(graph.graphState()))) {
            reasons.add("窗口内存在等待审批的动作，前端确认页和 permission-admin 应成为下一阶段关键集成点。");
        }
        return List.copyOf(reasons);
    }

    private List<String> recommendedActions(List<AgentToolActionExecutionGraphView> graphs) {
        java.util.ArrayList<String> actions = new java.util.ArrayList<>();
        actions.add("下一步优先实现正式 command builder：读取 graph/contract，补齐 payloadReference 和审批事实后写入 outbox。");
        actions.add("把图状态接入前端确认页，让用户看到审批、澄清、预算和证据缺口，而不是只看到工具是否 READY。");
        if (graphs.stream().anyMatch(graph ->
                AgentToolActionExecutionGraphPreviewBuilder.STATE_WAITING_BUDGET.equals(graph.graphState()))) {
            actions.add("建议补 task-management/worker backlog 快照，把预算等待从静态配置升级为实时容量判断。");
        }
        if (graphs.stream().anyMatch(graph -> graphBuilder.isBlockedGraphState(graph.graphState()))) {
            actions.add("存在阻断或入口拒绝图，优先排查工具目录、协议 adapter、权限策略和参数 schema。");
        }
        return List.copyOf(actions);
    }

    private Long countState(List<AgentToolActionExecutionGraphView> graphs, String state) {
        return graphs.stream().filter(graph -> state.equals(graph.graphState())).count();
    }

    private Map<String, Long> countNodeTypes(List<AgentToolActionExecutionGraphView> graphs) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (AgentToolActionExecutionGraphView graph : graphs) {
            for (AgentToolActionExecutionGraphNodeView node : safeList(graph.nodes())) {
                mergeCount(counts, node.nodeType());
            }
        }
        return Collections.unmodifiableMap(counts);
    }

    private Map<String, Long> countMissingRequirements(List<AgentToolActionExecutionGraphView> graphs) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (AgentToolActionExecutionGraphView graph : graphs) {
            for (String requirement : safeList(graph.missingRequirements())) {
                mergeCount(counts, requirement);
            }
        }
        return Collections.unmodifiableMap(counts);
    }

    private Map<String, Long> countBy(List<AgentToolActionExecutionGraphView> graphs,
                                      Function<AgentToolActionExecutionGraphView, String> mapper) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (AgentToolActionExecutionGraphView graph : graphs) {
            mergeCount(counts, mapper.apply(graph));
        }
        return Collections.unmodifiableMap(counts);
    }

    private void mergeCount(Map<String, Long> counts, String key) {
        if (key != null && !key.isBlank()) {
            counts.merge(key.trim(), 1L, Long::sum);
        }
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }
}
