/**
 * @Author : Cui
 * @Date: 2026/06/01 23:18
 * @Description DataSmart Govern Backend - AgentAsyncTaskCommandExecutionEvidence.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.execution;

import com.czh.datasmart.govern.agent.model.AgentHandoffDagBridgeSourceEvidence;

import java.util.List;

/**
 * Agent 异步命令执行前证据快照。
 *
 * <p>该对象是 agent-runtime 写入 command payload 前的内部值对象，用于把 selected-node confirmation、
 * permission-admin policyVersion 和 SERVICE_ACCOUNT 委托证据随 command 一起交给 task-management。
 * 它不是新的执行状态机，也不替代 confirmation 表；它的作用是让下游 worker 在真正执行前能看到“这条命令来自哪次确认”。</p>
 *
 * @param confirmationId selected-node 确认 ID；Run 级兼容入口可以为空
 * @param policyVersions 当前工具入箱时关联的权限策略版本快照
 * @param delegationEvidence 服务账号代表用户执行时的委托证据摘要
 */
public record AgentAsyncTaskCommandExecutionEvidence(
        String confirmationId,
        List<String> policyVersions,
        List<String> delegationEvidence,
        /**
         * selected-node dry-run 时由 Java 授权预检服务生成的决策码。
         *
         * <p>MCP 命令只接受 {@code PERMISSION_ADMIN_ALLOWED}。本地结构预览
         * {@code LOCAL_PREVIEW_ALLOWED} 只能帮助开发者检查上下文字段是否完整，不能被升级为真实外部工具权限。</p>
         */
        String serviceAuthorizationDecision,

        /**
         * 授权预检的显式布尔结果。
         *
         * <p>该字段必须与授权决策码、策略版本和委托证据一起判断，不能单独作为放行依据。这样可以避免
         * 调用方仅提交一个 {@code true} 就伪造 permission-admin 已经授权的事实。</p>
         */
        Boolean serviceAuthorizationAllowed,

        /*
         * 可选的 handoff DAG bridge preview 来源证据。
         *
         * 该字段会随 command payload 进入 task-management/worker 侧，用于解释“这条命令来自哪次
         * handoff tool-control 预检”。它不能替代 confirmationId 回查，也不能作为授权依据。
         */
        AgentHandoffDagBridgeSourceEvidence bridgeSourceEvidence
) {

    /**
     * 规范化执行证据字段。
     *
     * <p>record 的紧凑构造器会在每次创建对象时自动执行，适合放置“值对象自身必须始终成立”的基础约束。
     * 这里不做 permission-admin 远端复核，也不判断 confirmation 是否真实存在，因为那些属于 selected-node service
     * 和后续 worker pre-check 的业务职责；当前对象只负责保证进入 command payload 的证据是去空格、去空项、
     * 去重复后的低敏摘要，避免同一个策略版本或委托摘要被重复写入 Kafka payload。</p>
     */
    public AgentAsyncTaskCommandExecutionEvidence {
        confirmationId = normalize(confirmationId);
        policyVersions = policyVersions == null ? List.of() : policyVersions.stream()
                .filter(AgentAsyncTaskCommandExecutionEvidence::hasText)
                .map(String::trim)
                .distinct()
                .toList();
        delegationEvidence = delegationEvidence == null ? List.of() : delegationEvidence.stream()
                .filter(AgentAsyncTaskCommandExecutionEvidence::hasText)
                .map(String::trim)
                .distinct()
                .toList();
        serviceAuthorizationDecision = normalize(serviceAuthorizationDecision);
        serviceAuthorizationAllowed = Boolean.TRUE.equals(serviceAuthorizationAllowed);
        /*
         * bridgeSourceEvidence 允许为空，原因是历史 command、Run 级兼容入口和直接 Tool DAG dry-run 入口
         * 可能没有 handoff bridge 来源。它一旦存在，也只作为低敏审计上下文进入 payload；worker 仍必须
         * 通过 confirmationId 回查 confirmation 表，不能只凭该来源证据执行真实副作用。
         */
    }

    /**
     * 兼容历史调用方的四参数构造器。
     *
     * <p>旧调用方没有授权决策快照时按“未验证”处理，而不是默认放行。普通异步任务仍可沿用旧合同，
     * MCP 外部工具调用则会在入箱前 fail-closed，直到它走完整 selected-node + permission-admin 链路。</p>
     */
    public AgentAsyncTaskCommandExecutionEvidence(
            String confirmationId,
            List<String> policyVersions,
            List<String> delegationEvidence,
            AgentHandoffDagBridgeSourceEvidence bridgeSourceEvidence) {
        this(confirmationId, policyVersions, delegationEvidence, null, false, bridgeSourceEvidence);
    }

    /**
     * 返回一个“没有执行证据”的空对象。
     *
     * <p>Run 级兼容入口或历史 command 可能还没有 selected-node confirmation。
     * 使用空对象而不是到处返回 null，可以让 outbox 组装流程保持清晰：调用方始终拿到一个证据对象，
     * payload 组装时再根据 confirmationId 是否存在决定是否写入证据字段。</p>
     */
    public static AgentAsyncTaskCommandExecutionEvidence empty() {
        return new AgentAsyncTaskCommandExecutionEvidence(null, List.of(), List.of(), null, false, null);
    }

    /**
     * 把可选文本转成统一格式。
     *
     * <p>证据字段会跨越 agent-runtime、Kafka、task-management 和未来 worker，因此空字符串与 null
     * 不应在不同模块里产生不同语义。这里统一把空白文本折叠为 null，表示“该证据未提供”。</p>
     */
    private static String normalize(String value) {
        return hasText(value) ? value.trim() : null;
    }

    /**
     * 判断文本是否真正有业务内容。
     *
     * <p>该方法只做最基础的空白判断；敏感内容拦截、长度限制和字段数量限制放在 task-management 消费侧，
     * 因为消费侧是 command 进入任务事实前的最后一道契约防线。</p>
     */
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
