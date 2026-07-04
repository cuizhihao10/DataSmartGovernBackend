/**
 * @Author : Cui
 * @Date: 2026/07/05 01:22
 * @Description DataSmart Govern Backend - AgentRagCommandWorkerRunRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime.rag;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Java agent-runtime 投递给 Python RAG Command Worker 的请求合同。
 *
 * <p>该请求刻意保持为“arguments + controlFacts”的轻量结构，是为了和 Python
 * `/internal/agent/rag/command-worker/run` 的解析规则一致：</p>
 *
 * <p>1. `arguments` 是短生命周期执行入参。RAG 必须有 question 才能检索知识库，但 question 只能在本次
 * HTTP 调用内存中短暂存在，不能进入 Java outbox 低敏投影、worker receipt、runtime event、Prometheus label
 * 或错误信息；</p>
 *
 * <p>2. `controlFacts` 是 Java 控制面事实，包含 commandId、runId、sessionId、tenantId、projectId、
 * actorId、queryRef、payloadReference 等低敏字段；Python 只允许把这些白名单事实写回 receipt；</p>
 *
 * <p>3. `postToJava` 默认由客户端配置控制。当前推荐 false，让 Java dispatcher 自己写 receipt，
 * 避免 Python 直写和 Java 写入同时发生造成重复回执或状态难以解释。</p>
 *
 * @param arguments 本次 RAG worker 执行入参，允许包含短生命周期 question，但调用结果绝不能回显。
 * @param controlFacts Java 控制面低敏事实，用于生成 queryRef、receipt、checkpoint locator 和审计边界。
 * @param postToJava 是否要求 Python worker 直接回写 Java receipt；为空时使用客户端配置默认值。
 */
public record AgentRagCommandWorkerRunRequest(
        Map<String, Object> arguments,
        Map<String, Object> controlFacts,
        Boolean postToJava
) {

    public AgentRagCommandWorkerRunRequest {
        arguments = copyMap(arguments);
        controlFacts = copyMap(controlFacts);
    }

    /**
     * 套用客户端级默认执行选项。
     *
     * <p>调用方通常只关心 question、queryRef 和控制面定位事实。是否由 Python 直接 POST Java receipt
     * 是部署策略，适合集中放在配置里，而不是让每条命令都重复声明。</p>
     */
    public AgentRagCommandWorkerRunRequest withClientDefaults(boolean defaultPostToJava) {
        return new AgentRagCommandWorkerRunRequest(
                arguments,
                controlFacts,
                postToJava == null ? defaultPostToJava : postToJava
        );
    }

    private static Map<String, Object> copyMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Map.copyOf(new LinkedHashMap<>(source));
    }
}
