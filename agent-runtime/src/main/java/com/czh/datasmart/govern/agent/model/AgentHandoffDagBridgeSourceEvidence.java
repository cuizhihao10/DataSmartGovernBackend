/**
 * @Author : Cui
 * @Date: 2026/06/05 23:20
 * @Description DataSmart Govern Backend - AgentHandoffDagBridgeSourceEvidence.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.model;

import java.util.List;
import java.util.Locale;

/**
 * Handoff DAG bridge preview 来源证据。
 *
 * <p>该对象用于把 5.18 新增的 “handoff DAG -> Tool DAG dry-run bridge preview” 与后续
 * selected-node confirmation、command outbox、worker pre-check 串成一条低敏证据链。它回答的是：
 * “这次确认是不是来自 handoff DAG 上的 tool-control 预检入口？当时预检对应哪些 handoff 节点和工具节点？”
 * 而不是回答“工具要如何执行”。</p>
 *
 * <p>安全边界非常重要：本对象只能保存节点 ID、auditId、bridgeAction、fingerprint、traceId 等控制面摘要，
 * 不能保存 prompt、SQL、工具参数、targetEndpoint、Kafka topic、样例数据、模型输出或完整 request template。
 * 原因是该对象会进入 confirmation 审计表和 command payload，未来还可能被 task-management worker、
 * 审计台、WebSocket timeline 和管理员补偿台读取。如果在这里扩散敏感上下文，就会把“可观测性”变成新的泄露面。</p>
 *
 * @param sourceType 来源类型。当前固定为 {@link #SOURCE_TYPE_HANDOFF_DAG_BRIDGE_PREVIEW}，为未来 MCP/A2A/人工补偿来源预留扩展位。
 * @param bridgeAction bridge preview 的动作分类，例如 TOOL_CONTROL_DRY_RUN。
 * @param bridgeReady preview 当时是否可推进。真实确认仍会重新 dry-run，本字段只用于解释来源。
 * @param selectionFingerprint bridge preview 当时展示给调用方的 dry-run 指纹，selected-node confirmation 会要求它与当前 dry-run 一致。
 * @param handoffNodeIds 调用方在 handoff DAG 上选择的节点 ID，通常包含 tool-control。
 * @param mappedToolNodeIds bridge preview 映射到 Tool DAG dry-run 的 nodeId 选择器摘要。
 * @param mappedToolAuditIds bridge preview 映射到 Tool DAG dry-run 的 auditId 选择器摘要。
 * @param previewTraceId bridge preview 请求的 traceId，用于把 bridge event、confirmation 和 outbox 日志串联起来。
 * @param previewEventType bridge preview runtime event 类型，方便审计台从 confirmation 跳回 timeline 过滤同类事件。
 */
public record AgentHandoffDagBridgeSourceEvidence(
        String sourceType,
        String bridgeAction,
        Boolean bridgeReady,
        String selectionFingerprint,
        List<String> handoffNodeIds,
        List<String> mappedToolNodeIds,
        List<String> mappedToolAuditIds,
        String previewTraceId,
        String previewEventType
) {

    /**
     * 当前支持的来源类型。
     *
     * <p>字段使用显式枚举字符串，而不是仅靠对象是否存在来判断来源，是为了后续可以安全扩展：
     * 例如 “A2A_AGENT_TASK_HANDOFF”、“MCP_TOOL_SELECTION_PREVIEW”、“ADMIN_COMPENSATION_REPLAY”。
     * selected-node service 会对当前已知来源执行更严格校验，避免外部调用方伪造任意来源。</p>
     */
    public static final String SOURCE_TYPE_HANDOFF_DAG_BRIDGE_PREVIEW = "HANDOFF_DAG_BRIDGE_PREVIEW";

    /**
     * 当前唯一允许直接进入 selected-node confirmation 的 bridge 动作。
     *
     * <p>只有 tool-control 节点代表“进入工具治理链路”。Master、Memory、Feedback、Second Turn 等节点属于会话协作层，
     * 即便它们出现在 handoff DAG 上，也不能被当成真实工具执行确认来源。</p>
     */
    public static final String BRIDGE_ACTION_TOOL_CONTROL_DRY_RUN = "TOOL_CONTROL_DRY_RUN";

    public AgentHandoffDagBridgeSourceEvidence {
        sourceType = normalizeSourceType(sourceType);
        bridgeAction = normalizeText(bridgeAction);
        selectionFingerprint = normalizeText(selectionFingerprint);
        handoffNodeIds = normalizeList(handoffNodeIds);
        mappedToolNodeIds = normalizeList(mappedToolNodeIds);
        mappedToolAuditIds = normalizeList(mappedToolAuditIds);
        previewTraceId = normalizeText(previewTraceId);
        previewEventType = normalizeText(previewEventType);
    }

    /**
     * 构造 handoff bridge preview 来源证据。
     *
     * <p>桥接服务在生成 selected-node outbox request template 时使用该工厂方法。这样前端或 Agent Host
     * 可以原样把 {@code bridgeSourceEvidence} 携带到 selected-node endpoint；服务端仍会重新 dry-run 并校验
     * fingerprint、handoff 节点和 auditId 范围，所以它不是授权令牌，只是审计证据。</p>
     */
    public static AgentHandoffDagBridgeSourceEvidence handoffBridgePreview(String bridgeAction,
                                                                           Boolean bridgeReady,
                                                                           String selectionFingerprint,
                                                                           List<String> handoffNodeIds,
                                                                           List<String> mappedToolNodeIds,
                                                                           List<String> mappedToolAuditIds,
                                                                           String previewTraceId,
                                                                           String previewEventType) {
        return new AgentHandoffDagBridgeSourceEvidence(
                SOURCE_TYPE_HANDOFF_DAG_BRIDGE_PREVIEW,
                bridgeAction,
                bridgeReady,
                selectionFingerprint,
                handoffNodeIds,
                mappedToolNodeIds,
                mappedToolAuditIds,
                previewTraceId,
                previewEventType
        );
    }

    /**
     * 当前证据是否声明自己来自 handoff DAG bridge preview。
     */
    public boolean isHandoffDagBridgePreview() {
        return SOURCE_TYPE_HANDOFF_DAG_BRIDGE_PREVIEW.equals(sourceType);
    }

    /**
     * handoff 选择中是否包含 tool-control 节点。
     */
    public boolean containsToolControlNode() {
        return handoffNodeIds.stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch("tool-control"::equals);
    }

    private static String normalizeSourceType(String value) {
        String normalized = normalizeText(value);
        return normalized == null ? SOURCE_TYPE_HANDOFF_DAG_BRIDGE_PREVIEW : normalized;
    }

    private static String normalizeText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static List<String> normalizeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        /*
         * 来源证据是审计摘要，不是完整 DAG 快照。这里保守限制最多 50 个 ID，避免异常请求把确认记录和 command payload
         * 撑大。真实入箱数量仍由 dry-run maxNodes、outbox capacity guard 和 selected-node 校验控制。
         */
        return values.stream()
                .filter(AgentHandoffDagBridgeSourceEvidence::hasText)
                .map(String::trim)
                .distinct()
                .limit(50)
                .toList();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
