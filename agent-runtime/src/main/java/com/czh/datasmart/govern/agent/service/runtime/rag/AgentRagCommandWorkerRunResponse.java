/**
 * @Author : Cui
 * @Date: 2026/07/05 01:22
 * @Description DataSmart Govern Backend - AgentRagCommandWorkerRunResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime.rag;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Python RAG Command Worker 返回给 Java agent-runtime 的低敏响应合同。
 *
 * <p>该 DTO 只表达控制面可消费的摘要，不表达 RAG 正文。Python worker 的真实执行过程中可能产生：
 * question、answer、compressedContext、selected chunk text、citation snippet、sourceUri、模型消息和原始模型响应。
 * 这些内容都不能进入本 DTO。需要读取答案正文时，后续必须通过 MinIO/受控 artifact store 与 Java artifact grant
 * 链路完成二次授权。</p>
 *
 * @param schemaVersion Python 内部 worker API 协议版本。
 * @param accepted Python worker 是否接受并处理了请求。
 * @param workerResult RAG worker 低敏运行摘要，例如候选数、引用数、artifact 是否存在。
 * @param receipt Python 侧 receipt 摘要，包含 javaPayload 的低敏镜像。
 * @param javaReceiptPayload 可直接转换为 Java AgentToolActionCommandWorkerReceiptRequest 的白名单 payload。
 * @param postResult 当 Python 被要求直接回写 Java receipt 时的低敏 POST 结果；默认通常为空。
 * @param langGraphCheckpoint RAG LangGraph 节点链路 checkpoint locator 摘要，不包含 checkpoint state 正文。
 * @param payloadPolicy Python 声明的响应载荷策略，用于 Java 校验“没有返回 question/answer/context 正文”。
 */
public record AgentRagCommandWorkerRunResponse(
        String schemaVersion,
        Boolean accepted,
        Map<String, Object> workerResult,
        Map<String, Object> receipt,
        Map<String, Object> javaReceiptPayload,
        Map<String, Object> postResult,
        Map<String, Object> langGraphCheckpoint,
        String payloadPolicy
) {

    public AgentRagCommandWorkerRunResponse {
        workerResult = copyMap(workerResult);
        receipt = copyMap(receipt);
        javaReceiptPayload = copyMap(javaReceiptPayload);
        postResult = copyMap(postResult);
        langGraphCheckpoint = copyMap(langGraphCheckpoint);
    }

    private static Map<String, Object> copyMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Map.copyOf(new LinkedHashMap<>(source));
    }
}
