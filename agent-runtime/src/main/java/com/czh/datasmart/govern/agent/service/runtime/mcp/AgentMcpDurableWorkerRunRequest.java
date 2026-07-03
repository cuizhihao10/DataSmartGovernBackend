/**
 * @Author : Cui
 * @Date: 2026/07/03 16:46
 * @Description DataSmart Govern Backend - AgentMcpDurableWorkerRunRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime.mcp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Java agent-runtime 投递给 Python MCP Durable Worker 的运行请求。
 *
 * <p>这个请求是 Java 控制面与 Python 执行面的“窄合同”：Java 只把经过 outbox、permission、approval、readiness 等控制面
 * 校验后的低敏事实和短生命周期工具参数交给 Python。Python 再根据这些事实构造 MCP admission，并调用真实 MCP Server。
 * 这样设计可以避免模型、前端或普通 API 调用方直接声明“我已经被授权”，授权事实必须来自 Java 控制面。</p>
 *
 * <p>字段设计原则：</p>
 * <p>1. {@code arguments} 是短生命周期工具参数，只用于本次 Python worker 调用，不应该进入 Java 低敏日志或事件；</p>
 * <p>2. {@code controlFacts} 是 Java 可信控制事实，例如 tenant/project/workspace/run/call、readiness、permission、approval；</p>
 * <p>3. {@code fallbackContext} 只用于缺字段时的低敏补充，不应该包含 prompt、SQL、样本数据、凭据或工具正文；</p>
 * <p>4. {@code includeModelFeedback} 只表示 Java 希望 Python 返回可供模型二轮消费的安全摘要，最终是否内联结果由 Python 预算决定。</p>
 *
 * @param serverId MCP Server 的逻辑 ID，例如 enterprise-search。它是目录命名空间，不是 URL。
 * @param internalToolName DataSmart 内部工具名，例如 mcp.enterprise.search。
 * @param arguments 本次工具调用参数，属于短生命周期正文，调用结果中绝不回显。
 * @param controlFacts Java 控制面事实，Python admission builder 会据此判断是否允许执行。
 * @param fallbackContext 低敏补充上下文，用于兼容历史字段或调试缺失事实。
 * @param postToJava 是否要求 Python worker 直接回写 Java receipt，默认由 client properties 决定。
 * @param sessionId Agent 会话 ID，用于 timeline、checkpoint 和前端订阅聚合。
 * @param traceId 链路追踪 ID，会透传到 Python，便于跨服务排障。
 * @param toolCallId 本次工具调用 ID，用于 MCP 工具结果、receipt 和模型 feedback 关联。
 * @param workspaceKey 工作区边界，避免多租户/多项目 Agent 执行时串用工具权限。
 * @param currentWorkspaceKey 兼容 Python Runtime 历史命名的工作区字段。
 * @param includeModelFeedback 是否请求 Python 返回安全模型二轮反馈摘要。
 */
public record AgentMcpDurableWorkerRunRequest(
        String serverId,
        String internalToolName,
        Map<String, Object> arguments,
        Map<String, Object> controlFacts,
        Map<String, Object> fallbackContext,
        Boolean postToJava,
        String sessionId,
        String traceId,
        String toolCallId,
        String workspaceKey,
        String currentWorkspaceKey,
        Boolean includeModelFeedback
) {

    public AgentMcpDurableWorkerRunRequest {
        arguments = copyMap(arguments);
        controlFacts = copyMap(controlFacts);
        fallbackContext = copyMap(fallbackContext);
    }

    /**
     * 套用 Java client 的默认执行选项。
     *
     * <p>调用方通常只关心“要执行哪个 MCP 工具”和“携带哪些控制事实”。是否让 Python 直接回写 Java、是否返回模型二轮反馈，
     * 是部署和链路策略问题，因此放在 client properties 中统一控制。若请求显式传入值，则尊重请求级覆盖，便于灰度验证。</p>
     */
    public AgentMcpDurableWorkerRunRequest withClientDefaults(boolean defaultPostToJava,
                                                              boolean defaultIncludeModelFeedback) {
        return new AgentMcpDurableWorkerRunRequest(
                serverId,
                internalToolName,
                arguments,
                controlFacts,
                fallbackContext,
                postToJava == null ? defaultPostToJava : postToJava,
                sessionId,
                traceId,
                toolCallId,
                workspaceKey,
                currentWorkspaceKey,
                includeModelFeedback == null ? defaultIncludeModelFeedback : includeModelFeedback
        );
    }

    private static Map<String, Object> copyMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Map.copyOf(new LinkedHashMap<>(source));
    }
}
