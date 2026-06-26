/**
 * @Author : Cui
 * @Date: 2026/06/26 21:08
 * @Description DataSmart Govern Backend - AgentToolActionArtifactBodyReadGrantRevokeResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * artifact 正文读取 grant fact 撤销响应。
 */
public record AgentToolActionArtifactBodyReadGrantRevokeResponse(
        /** 是否已撤销。 */
        Boolean revoked,

        /** 撤销决策码。 */
        String decision,

        /** 撤销后的低敏 grant fact 视图。 */
        AgentToolActionArtifactBodyReadGrantFactView grant,

        /** 撤销链路证据码。 */
        List<String> evidenceCodes,

        /** 撤销失败或需要注意的问题码。 */
        List<String> issueCodes,

        /** 后续建议动作。 */
        List<String> recommendedActions
) {
}
