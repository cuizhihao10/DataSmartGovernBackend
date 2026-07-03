/**
 * @Author : Cui
 * @Date: 2026/07/03 16:46
 * @Description DataSmart Govern Backend - AgentMcpDurableWorkerRunResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime.mcp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Python MCP Durable Worker 返回给 Java agent-runtime 的低敏响应合同。
 *
 * <p>该 DTO 只表达 Python 已经完成的“可审计摘要”，不表达工具正文。真实 MCP 返回内容可能来自文件系统、企业搜索、
 * SaaS API、数据库或其他外部工具，存在敏感数据、体积过大、版权或合规风险。因此 Java 侧只接收 Python 已治理过的
 * {@code workerResult}、{@code receipt} 和可选 {@code modelFeedback}。如果后续需要读取大结果，应通过 MinIO/S3 artifact
 * 引用和授权 resolver，而不是把正文塞回控制面。</p>
 *
 * @param schemaVersion Python 内部 API 响应协议版本。
 * @param accepted Python worker 是否接受并处理了请求。
 * @param workerResult MCP worker 的低敏运行摘要，包含状态、大小、哈希、截断标记等，不包含正文。
 * @param receipt 可写回 Java receipt 的低敏摘要。
 * @param modelFeedback 可选的模型二轮反馈摘要，只有 Python 判定安全时才可能含短结果。
 * @param payloadPolicy Python 声明的载荷策略，例如 MCP_ARGUMENTS_NEVER_RETURNED。
 */
public record AgentMcpDurableWorkerRunResponse(
        String schemaVersion,
        Boolean accepted,
        Map<String, Object> workerResult,
        Map<String, Object> receipt,
        Map<String, Object> modelFeedback,
        String payloadPolicy
) {

    public AgentMcpDurableWorkerRunResponse {
        workerResult = copyMap(workerResult);
        receipt = copyMap(receipt);
        modelFeedback = copyMap(modelFeedback);
    }

    private static Map<String, Object> copyMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Map.copyOf(new LinkedHashMap<>(source));
    }
}
