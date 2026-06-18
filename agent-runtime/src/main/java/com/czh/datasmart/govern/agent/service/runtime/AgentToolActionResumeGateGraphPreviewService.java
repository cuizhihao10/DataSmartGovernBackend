/**
 * @Author : Cui
 * @Date: 2026/06/18 01:39
 * @Description DataSmart Govern Backend - AgentToolActionResumeGateGraphPreviewService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionExecutionGraphNodeView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionResumeFactBundleQueryRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionResumeFactBundleResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionResumeGateGraphQueryResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionResumeGateGraphView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Agent 工具动作恢复门控图预览服务。
 *
 * <p>本服务位于恢复事实包之上，是从“事实列表”走向“OpenClaw/LangGraph 风格条件图”的过渡层。
 * 它复用 {@link AgentToolActionResumeFactBundleService} 已有的事实验真能力，再把结果转换为低敏图结构。
 * 这样可以避免重复实现审批、澄清、outbox、worker receipt 等事实源，也能保证恢复门控图与事实包使用同一套
 * tenant/project/actor 数据范围收口规则。</p>
 *
 * <p>重要边界：该服务仍然是 preview/query。它不会执行工具、不会写 outbox、不会派发 worker、不会修改 checkpoint。
 * 如果后续新增真实恢复执行，应新增独立 command/runner 服务，并把本服务的图作为“解释与预检输入”，而不是直接复用为执行器。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentToolActionResumeGateGraphPreviewService {

    public static final String RESPONSE_SCHEMA_VERSION =
            "datasmart.agent-runtime.tool-action-resume-gate-graph-query.v1";

    private final AgentToolActionResumeFactBundleService factBundleService;
    private final AgentToolActionResumeGateGraphBuilder graphBuilder = new AgentToolActionResumeGateGraphBuilder();

    /**
     * 查询恢复门控图预览。
     *
     * <p>方法流程分三步：
     * 1. 先调用事实包服务完成服务端事实验真和权限范围收口；
     * 2. 再由 builder 把事实状态转换为节点和边；
     * 3. 最后补充窗口级统计，方便前端、审计台和后续低基数指标消费。</p>
     *
     * @param request 恢复事实定位请求，只允许低敏 locator 字段。
     * @param accessContext 当前租户、actor、角色和数据范围上下文。
     * @return 单张恢复门控图和聚合统计。
     */
    public AgentToolActionResumeGateGraphQueryResponse query(
            AgentToolActionResumeFactBundleQueryRequest request,
            AgentRuntimeEventQueryAccessContext accessContext) {
        AgentToolActionResumeFactBundleResponse factBundle = factBundleService.query(request, accessContext);
        AgentToolActionResumeGateGraphView graph = graphBuilder.build(factBundle);
        return new AgentToolActionResumeGateGraphQueryResponse(
                RESPONSE_SCHEMA_VERSION,
                true,
                factBundle.queryBoundary(),
                factBundle.payloadPolicy(),
                graph.graphState(),
                graph.terminalState(),
                graph.resumePreviewReady(),
                safeSize(graph.requiredFactTypes()),
                safeSize(graph.availableFactTypes()),
                safeSize(graph.missingFactTypes()),
                safeSize(graph.rejectedFactTypes()),
                graph.nodeCount(),
                graph.edgeCount(),
                countBy(graph.nodes(), AgentToolActionExecutionGraphNodeView::nodeType),
                countBy(graph.nodes(), AgentToolActionExecutionGraphNodeView::status),
                graph.summaryReasons(),
                graph.recommendedActions(),
                factBundle.productionReadiness(),
                graph,
                factBundle.generatedAt()
        );
    }

    private Map<String, Long> countBy(Iterable<AgentToolActionExecutionGraphNodeView> nodes,
                                      Function<AgentToolActionExecutionGraphNodeView, String> mapper) {
        Map<String, Long> counts = new LinkedHashMap<>();
        if (nodes == null) {
            return Map.of();
        }
        for (AgentToolActionExecutionGraphNodeView node : nodes) {
            String key = mapper.apply(node);
            if (key != null && !key.isBlank()) {
                counts.merge(key.trim(), 1L, Long::sum);
            }
        }
        return Collections.unmodifiableMap(counts);
    }

    private int safeSize(java.util.List<?> values) {
        return values == null ? 0 : values.size();
    }
}
