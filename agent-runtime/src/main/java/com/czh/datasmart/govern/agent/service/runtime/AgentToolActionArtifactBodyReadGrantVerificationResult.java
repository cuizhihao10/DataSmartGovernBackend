/**
 * @Author : Cui
 * @Date: 2026/06/26 20:27
 * @Description DataSmart Govern Backend - AgentToolActionArtifactBodyReadGrantVerificationResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import java.util.List;

/**
 * grant fact 回查验证结果。
 *
 * <p>final-check 和 object-store probe 都需要同一套验证语义：引用存在、状态有效、未过期、上下文一致。
 * 用独立结果对象承载 evidence/issue/action，可以避免两个服务各写一套略有差异的拒绝原因，
 * 也便于后续把验证结果写入 runtime event 或审计中心。</p>
 */
public record AgentToolActionArtifactBodyReadGrantVerificationResult(
        /**
         * 是否通过服务端事实回查。
         *
         * <p>true 表示 grant fact 存在、未过期、未撤销，并且关键上下文与当前 re-grant 决策一致；
         * false 表示后续 final-check/probe 必须 fail-closed。</p>
         */
        boolean verified,
        /**
         * 面向控制面和审计的机器决策码。
         *
         * <p>示例：STORED_BODY_READ_GRANT_VERIFIED、DENIED_STORED_BODY_READ_GRANT_NOT_FOUND。
         * controller 或上层服务可以把它展示为低敏状态，而不需要暴露具体 artifact 内容。</p>
         */
        String decision,
        /**
         * 命中的服务端 grant fact。
         *
         * <p>验证失败时也可能返回 record，用于解释“已找到但过期/撤销/上下文不一致”；
         * 未找到记录时该字段为空。</p>
         */
        AgentToolActionArtifactBodyReadGrantRecord record,
        /**
         * 支撑决策的低敏证据码集合。
         *
         * <p>这些 code 可以进入响应、runtime event 或测试断言，但不能包含 prompt、SQL、工具实参、
         * artifact 正文、bucket/key、URL 或 token 明文。</p>
         */
        List<String> evidenceCodes,
        /**
         * 验证失败时的低敏问题码集合。
         *
         * <p>issueCode 让调用方知道应该重新申请 grant、等待审批、联系管理员还是停止读取，
         * 但不会把真实路径、对象定位或原始异常暴露出去。</p>
         */
        List<String> issueCodes,
        /**
         * 建议调用方或操作员采取的下一步动作。
         *
         * <p>例如 REQUEST_NEW_BODY_READ_GRANT、CONTACT_ADMIN、STOP_OBJECT_STORE_PROBE。
         * 推荐动作是产品层可解释性的一部分，帮助前端/运维台给出清晰提示。</p>
         */
        List<String> recommendedActions
) {

    /**
     * 构造验证通过结果。
     *
     * <p>通过结果统一使用 STORED_BODY_READ_GRANT_VERIFIED，避免 final-check 与 object-store probe
     * 分别发明不同成功码，降低审计和指标聚合成本。</p>
     */
    public static AgentToolActionArtifactBodyReadGrantVerificationResult verified(
            AgentToolActionArtifactBodyReadGrantRecord record,
            List<String> evidenceCodes) {
        return new AgentToolActionArtifactBodyReadGrantVerificationResult(
                true,
                "STORED_BODY_READ_GRANT_VERIFIED",
                record,
                List.copyOf(evidenceCodes),
                List.of(),
                List.of()
        );
    }

    /**
     * 构造验证拒绝结果。
     *
     * <p>拒绝结果必须携带 issueCodes 和 recommendedActions，这样调用方可以做安全降级：
     * 例如不调用对象存储 adapter、不返回正文预览、提示重新申请授权或进入人工处理。</p>
     */
    public static AgentToolActionArtifactBodyReadGrantVerificationResult denied(
            String decision,
            AgentToolActionArtifactBodyReadGrantRecord record,
            List<String> evidenceCodes,
            List<String> issueCodes,
            List<String> recommendedActions) {
        return new AgentToolActionArtifactBodyReadGrantVerificationResult(
                false,
                decision,
                record,
                List.copyOf(evidenceCodes),
                List.copyOf(issueCodes),
                List.copyOf(recommendedActions)
        );
    }
}
