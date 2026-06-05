/**
 * @Author : Cui
 * @Date: 2026/06/01 10:31
 * @Description DataSmart Govern Backend - AgentRunToolDagSelectedNodeOutboxEnqueueRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import com.czh.datasmart.govern.agent.model.AgentHandoffDagBridgeSourceEvidence;

import java.util.List;
import java.util.Map;

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
 * @param expectedPolicyVersionsByAuditId 调用方在上一次 dry-run 中看到的 permission-admin 策略版本快照；
 *                                        key 是 auditId，value 是策略版本号。真实确认时服务端会重新 dry-run，
 *                                        如果当前授权策略已经变化，就拒绝整批入箱，避免“用户确认的是旧权限，系统执行的是新权限”。
 * @param confirmed 必须显式为 true；用于区分“查看预案”和“确认产生 outbox 副作用”。
 */
public record AgentRunToolDagSelectedNodeOutboxEnqueueRequest(
        List<String> nodeIds,
        List<String> auditIds,
        Integer maxNodes,
        String expectedDryRunFingerprint,
        Map<String, String> expectedPolicyVersionsByAuditId,
        Boolean confirmed,
        /*
         * 可选的 handoff DAG bridge preview 来源证据。
         *
         * 该字段不是授权令牌，也不替代服务端重新 dry-run。它只用于把“handoff DAG tool-control 预检”
         * 与“selected-node confirmation”串成可审计证据链。服务端会校验其中的 fingerprint、handoff 节点
         * 和 auditId 范围；校验失败时会 fail-closed，避免调用方用旧 bridge 或伪造来源推进真实副作用。
         */
        AgentHandoffDagBridgeSourceEvidence bridgeSourceEvidence
) {
}
