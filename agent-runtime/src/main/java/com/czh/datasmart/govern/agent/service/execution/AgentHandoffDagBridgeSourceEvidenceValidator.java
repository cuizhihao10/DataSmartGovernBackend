/**
 * @Author : Cui
 * @Date: 2026/06/05 23:58
 * @Description DataSmart Govern Backend - AgentHandoffDagBridgeSourceEvidenceValidator.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.execution;

import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolDagExecutionDryRunResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolDagSelectedNodeOutboxEnqueueRequest;
import com.czh.datasmart.govern.agent.model.AgentHandoffDagBridgeSourceEvidence;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Handoff DAG bridge 来源证据校验器。
 *
 * <p>这个类存在的原因，是把 selected-node outbox 主服务中的“来源证据治理”拆出来，避免一个服务同时承担
 * dry-run 编排、指纹校验、策略版本校验、outbox 写入、confirmation 持久化和 handoff bridge 来源判断等过多职责。
 * 对商业化 Agent 平台来说，来源证据会不断扩展：当前是 handoff DAG bridge preview，未来可能会有 MCP tool
 * adapter、A2A agent task、人工补偿、审批任务、自动 worker 恢复等来源。如果所有来源规则都塞进 outbox service，
 * 该服务会很快超过 500 行并形成高耦合。</p>
 *
 * <p>本类的核心原则是：bridgeSourceEvidence 只是“低敏来源证据”，不是授权令牌，也不是执行许可。
 * 即便调用方从 bridge preview 的请求模板中复制了该对象，真正 selected-node 入箱时仍必须重新经过当前服务端
 * Tool DAG dry-run、selectionFingerprint、permission-admin policyVersion、async outbox candidate 和容量治理。
 * 本校验器只负责补充回答一个问题：这次确认是否仍然来自同一轮 handoff bridge preview，并且没有扩大 bridge
 * 当时映射出来的工具节点或审计项范围。</p>
 */
@Component
public class AgentHandoffDagBridgeSourceEvidenceValidator {

    /**
     * 校验 handoff DAG bridge preview 来源证据，并返回可继续写入 confirmation/outbox payload 的低敏证据对象。
     *
     * <p>允许 evidence 为空，是为了兼容两类合法路径：</p>
     * <p>1. 老版本调用方只从 Tool DAG dry-run 直接进入 selected-node confirmation，还没有 handoff bridge 概念；</p>
     * <p>2. 管理员或内部调试入口直接针对工具 DAG 做确认，不一定经过会话级 handoff DAG。</p>
     *
     * <p>当 evidence 非空时，本方法采用 fail-closed 策略：来源类型未知、bridgeAction 不匹配、bridgeReady 为 false、
     * 缺少 tool-control、fingerprint 过期、auditId 或 nodeId 超出 bridge 映射范围，都会拒绝入箱。这样可以防止
     * “展示层跳转证据”被误用为“扩大工具副作用范围”的通道。</p>
     *
     * @param evidence 调用方提交的可选来源证据；为空表示不声明 handoff bridge 来源。
     * @param dryRun selected-node 确认瞬间由服务端重新计算的 Tool DAG dry-run 结果，是当前事实源。
     * @param request selected-node 原始请求，用于比较调用方选择的 nodeId 是否超过 bridge preview 映射范围。
     * @param selectedAuditIds 服务端最终判定可进入 async outbox 的 auditId 集合。
     * @return 已通过校验、可以继续写入审计链路的来源证据；或者 null。
     */
    public AgentHandoffDagBridgeSourceEvidence validate(
            AgentHandoffDagBridgeSourceEvidence evidence,
            AgentRunToolDagExecutionDryRunResponse dryRun,
            AgentRunToolDagSelectedNodeOutboxEnqueueRequest request,
            Set<String> selectedAuditIds) {
        if (evidence == null) {
            return null;
        }
        ensureKnownHandoffBridgeSource(evidence);
        ensureBridgeWasReadyForToolControl(evidence);
        ensureFingerprintMatches(evidence.selectionFingerprint(), dryRun == null ? null : dryRun.selectionFingerprint());
        ensureSelectedAuditsWithinBridgeMapping(evidence, selectedAuditIds);
        ensureRequestedNodesWithinBridgeMapping(evidence, request);
        return evidence;
    }

    /**
     * 校验来源类型，避免未来新增的 MCP/A2A/人工补偿来源在没有专门规则时误用 handoff bridge 规则。
     */
    private void ensureKnownHandoffBridgeSource(AgentHandoffDagBridgeSourceEvidence evidence) {
        if (!evidence.isHandoffDagBridgePreview()) {
            throw new PlatformBusinessException(
                    PlatformErrorCode.BAD_REQUEST,
                    "DAG selected-node 入箱暂不支持未知 bridgeSourceEvidence.sourceType=" + evidence.sourceType()
            );
        }
    }

    /**
     * 校验 bridge preview 当时确实来自 tool-control dry-run，且当时状态允许推进到工具预检。
     *
     * <p>这里不接受 feedback、memory、second-turn 等会话级节点，是因为这些节点表达的是 Agent 协作或二轮推理语义，
     * 不能直接映射为工具副作用。如果未来要支持这些节点，应先设计新的状态机和审批规则，而不是复用 tool-control 规则。</p>
     */
    private void ensureBridgeWasReadyForToolControl(AgentHandoffDagBridgeSourceEvidence evidence) {
        if (!AgentHandoffDagBridgeSourceEvidence.BRIDGE_ACTION_TOOL_CONTROL_DRY_RUN.equals(evidence.bridgeAction())) {
            throw new PlatformBusinessException(
                    PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "handoff bridge 来源不是 TOOL_CONTROL_DRY_RUN，不能作为 selected-node confirmation 来源"
            );
        }
        if (!Boolean.TRUE.equals(evidence.bridgeReady())) {
            throw new PlatformBusinessException(
                    PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "handoff bridge 来源当时并不可推进，不能作为 selected-node confirmation 来源"
            );
        }
        if (!evidence.containsToolControlNode()) {
            throw new PlatformBusinessException(
                    PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "handoff bridge 来源缺少 tool-control 节点，不能映射到工具执行确认"
            );
        }
    }

    /**
     * 校验 bridge preview 的 selectionFingerprint 与当前重新 dry-run 的 fingerprint 一致。
     *
     * <p>selectionFingerprint 不是认证签名，但它是预案漂移检测点：如果 bridge preview 后工具候选、依赖、
     * 授权策略、运行时保护或筛选范围发生变化，fingerprint 应该变化，旧证据就不能继续推动副作用。</p>
     */
    private void ensureFingerprintMatches(String expectedFingerprint, String actualFingerprint) {
        if (!hasText(expectedFingerprint)) {
            throw new PlatformBusinessException(
                    PlatformErrorCode.BAD_REQUEST,
                    "handoff bridge 来源缺少 selectionFingerprint，无法证明它对应哪次 Tool DAG dry-run"
            );
        }
        if (!hasText(actualFingerprint)) {
            throw new PlatformBusinessException(
                    PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "当前 Tool DAG dry-run 缺少 selectionFingerprint，无法校验 handoff bridge 来源"
            );
        }
        boolean matched = MessageDigest.isEqual(
                expectedFingerprint.getBytes(StandardCharsets.UTF_8),
                actualFingerprint.getBytes(StandardCharsets.UTF_8)
        );
        if (!matched) {
            throw new PlatformBusinessException(
                    PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "handoff bridge 来源对应的 Tool DAG dry-run 预案已变化，请重新 bridge preview 并再次确认"
            );
        }
    }

    /**
     * 校验 selected-node 最终确认的 auditId 没有超出 bridge preview 当时映射出的工具审计项范围。
     *
     * <p>mappedToolAuditIds 为空时不强制拒绝，是为了兼容早期 bridge preview 只传 nodeId 的场景；但只要 evidence
     * 携带了 auditId 映射，本方法就会要求最终确认的 selectedAuditIds 是其子集。</p>
     */
    private void ensureSelectedAuditsWithinBridgeMapping(
            AgentHandoffDagBridgeSourceEvidence evidence,
            Set<String> selectedAuditIds) {
        Set<String> mappedAuditIds = new LinkedHashSet<>(safeList(evidence.mappedToolAuditIds()));
        Set<String> selected = selectedAuditIds == null ? Set.of() : selectedAuditIds;
        if (!mappedAuditIds.isEmpty() && !mappedAuditIds.containsAll(selected)) {
            throw new PlatformBusinessException(
                    PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "selected-node 确认的 auditId 超出 handoff bridge preview 映射范围，拒绝入箱"
            );
        }
    }

    /**
     * 校验调用方请求中的 nodeId 没有超出 bridge preview 当时映射出的工具节点范围。
     *
     * <p>该校验面向“按 nodeId 确认”的前端和 Agent Host 场景。若调用方只按 auditId 确认，则由
     * {@link #ensureSelectedAuditsWithinBridgeMapping(AgentHandoffDagBridgeSourceEvidence, Set)} 保护。</p>
     */
    private void ensureRequestedNodesWithinBridgeMapping(
            AgentHandoffDagBridgeSourceEvidence evidence,
            AgentRunToolDagSelectedNodeOutboxEnqueueRequest request) {
        List<String> requestedNodeIds = normalizeSelectors(request == null ? null : request.nodeIds());
        Set<String> mappedNodeIds = new LinkedHashSet<>(safeList(evidence.mappedToolNodeIds()));
        if (!mappedNodeIds.isEmpty() && !requestedNodeIds.isEmpty() && !mappedNodeIds.containsAll(requestedNodeIds)) {
            throw new PlatformBusinessException(
                    PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "selected-node 确认的 nodeId 超出 handoff bridge preview 映射范围，拒绝入箱"
            );
        }
    }

    private List<String> normalizeSelectors(List<String> selectors) {
        if (selectors == null) {
            return List.of();
        }
        return selectors.stream()
                .filter(this::hasText)
                .map(String::trim)
                .distinct()
                .toList();
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
