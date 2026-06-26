/**
 * @Author : Cui
 * @Date: 2026/06/26 21:08
 * @Description DataSmart Govern Backend - AgentToolActionArtifactBodyReadGrantRevokeRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

/**
 * artifact 正文读取 grant fact 撤销请求。
 *
 * <p>请求体只允许提交 grant 引用和原因码，不允许提交 revokedBy。撤销操作者必须来自 gateway 可信 Header，
 * 避免调用方在 body 中冒充其他管理员或服务账号。</p>
 */
public record AgentToolActionArtifactBodyReadGrantRevokeRequest(
        /** 要撤销的低敏 grant 引用。 */
        String grantDecisionReference,

        /** 机器可读原因码，例如 RISK_POLICY_CHANGED、ARTIFACT_QUARANTINED。 */
        String reasonCode
) {
}
