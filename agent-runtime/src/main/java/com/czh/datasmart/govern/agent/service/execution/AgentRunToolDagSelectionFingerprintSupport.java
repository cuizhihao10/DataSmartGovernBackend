/**
 * @Author : Cui
 * @Date: 2026/06/01 10:30
 * @Description DataSmart Govern Backend - AgentRunToolDagSelectionFingerprintSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.execution;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolDagExecutionDryRunItemView;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * DAG 执行选择指纹计算器。
 *
 * <p>该组件解决的是“用户看到的 dry-run 预案”和“稍后真正写入 outbox 的候选”之间可能发生漂移的问题。
 * 在商业化 Agent 产品中，用户点击确认之前，依赖节点、审批状态、服务间授权、工具幂等声明或批量上限都可能变化。
 * 如果服务端只相信前端回传的 nodeId，就可能拿着已经过期的预案继续创建异步任务。</p>
 *
 * <p>因此，dry-run 会基于服务端重新计算出的候选集合生成指纹；确认入箱时，服务端再次执行 dry-run，
 * 并要求调用方带回的旧指纹与新指纹完全一致。指纹相同只代表“预案没有漂移”，不代表“权限可以省略”：
 * 真正执行前仍需要继续经过 preview、permission-admin、outbox dispatcher 和 task-management worker。</p>
 */
@Component
public class AgentRunToolDagSelectionFingerprintSupport {

    private static final String FINGERPRINT_PREFIX = "dag-selection:";

    /**
     * 计算一次 DAG dry-run 的稳定指纹。
     *
     * <p>指纹只纳入会影响执行安全性的稳定字段，不纳入 reasons、recommendedActions 和 executionPath。
     * 后三者属于展示解释文本，后续文案优化不应让同一份执行预案失效。节点列表会先排序，避免前端选择顺序不同
     * 导致相同集合得到不同摘要。</p>
     *
     * @param sessionId 会话 ID，防止跨会话复用确认。
     * @param runId Run ID，防止跨编排轮次复用确认。
     * @param requestedNodeIds 调用方提交并经服务端规范化后的节点选择器。
     * @param requestedAuditIds 调用方提交并经服务端规范化后的审计选择器。
     * @param effectiveMaxNodes 服务端采用的批量上限。
     * @param items 服务端重新解释后的 dry-run 明细。
     * @return 带业务前缀的 SHA-256 十六进制摘要。
     */
    public String fingerprint(String sessionId,
                              String runId,
                              List<String> requestedNodeIds,
                              List<String> requestedAuditIds,
                              int effectiveMaxNodes,
                              List<AgentToolDagExecutionDryRunItemView> items) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, sessionId);
        append(canonical, runId);
        append(canonical, String.valueOf(effectiveMaxNodes));
        appendSorted(canonical, requestedNodeIds);
        appendSorted(canonical, requestedAuditIds);
        items.stream()
                .map(this::canonicalItem)
                .sorted()
                .forEach(value -> append(canonical, value));
        return FINGERPRINT_PREFIX + sha256(canonical.toString());
    }

    /**
     * 把单个节点压缩为稳定安全字段串。
     *
     * <p>这里包含 preview/dry-run 动作、授权结论、幂等声明和异步 commandId。只要其中任何一项变化，
     * 旧确认就会失效。工具参数值不会进入指纹原文，更不会进入响应，避免把敏感治理上下文扩散到客户端。</p>
     */
    private String canonicalItem(AgentToolDagExecutionDryRunItemView item) {
        return String.join("|",
                text(item.nodeId()),
                text(item.auditId()),
                text(item.toolCode()),
                text(item.previewAction()),
                text(item.dryRunAction()),
                text(item.readyForExecution()),
                text(item.asyncDispatchable()),
                text(item.asyncCommandId()),
                text(item.serviceAuthorizationDecision()),
                text(item.serviceAuthorizationAllowed()),
                text(item.riskLevel()),
                text(item.idempotent()),
                text(item.requiresApproval())
        );
    }

    private void appendSorted(StringBuilder canonical, List<String> values) {
        if (values == null || values.isEmpty()) {
            append(canonical, "");
            return;
        }
        values.stream().sorted().forEach(value -> append(canonical, value));
    }

    /**
     * 使用“长度 + 内容”的编码方式拼接字段。
     *
     * <p>不能只用普通分隔符拼接，因为字段本身未来可能包含分隔符，造成不同输入得到相同规范串。
     * 长度前缀让摘要输入具备明确边界。</p>
     */
    private void append(StringBuilder canonical, String value) {
        String safeValue = text(value);
        canonical.append(safeValue.length()).append(':').append(safeValue).append(';');
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256，无法生成 DAG 执行选择指纹", exception);
        }
    }
}
