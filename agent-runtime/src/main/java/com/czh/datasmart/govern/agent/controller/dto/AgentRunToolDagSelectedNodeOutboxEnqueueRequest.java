/**
 * @Author : Cui
 * @Date: 2026/06/01 10:31
 * @Description DataSmart Govern Backend - AgentRunToolDagSelectedNodeOutboxEnqueueRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * DAG 选中节点异步 outbox 确认入箱请求。
 *
 * <p>该请求用于把 dry-run 中已经明确展示过的异步节点送入受控 outbox。它刻意不包含 targetEndpoint、
 * targetService、Kafka topic 或工具参数：这些字段必须由 Java 控制面根据 auditId 重新读取可信事实，
 * 不能由模型、前端或外部调用方临时拼接，否则会把受控工具调用退化为任意内部 HTTP 转发器。</p>
 *
 * @param nodeIds 必填选择范围之一；来自最新 DAG dry-run 的节点 ID。
 * @param auditIds 必填选择范围之一；来自最新 DAG dry-run 的工具审计 ID。
 * @param maxNodes 本次最多确认多少个节点；服务端仍会应用默认值和硬上限。
 * @param expectedDryRunFingerprint 调用方在上一次 dry-run 响应中看到的选择指纹，用于防止过期预案继续入箱。
 * @param confirmed 必须显式为 true；用于区分“查看预案”和“确认产生 outbox 副作用”。
 */
public record AgentRunToolDagSelectedNodeOutboxEnqueueRequest(
        List<String> nodeIds,
        List<String> auditIds,
        Integer maxNodes,
        String expectedDryRunFingerprint,
        Boolean confirmed
) {
}
