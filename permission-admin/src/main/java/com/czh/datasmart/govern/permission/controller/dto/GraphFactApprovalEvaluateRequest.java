/**
 * @Author : Cui
 * @Date: 2026/08/21 10:00
 * @Description DataSmart Govern Backend - GraphFactApprovalEvaluateRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

import lombok.Data;

/**
 * Python 图事实 consumer 回查审批事实时使用的请求。
 *
 * <p>字段复制自双主体审批绑定，确保一个图事实审批不能跨应用、项目、Agent session 或 run 重放。</p>
 */
@Data
public class GraphFactApprovalEvaluateRequest {

    private String approvalFactId;
    private Long tenantId;
    private Long applicationId;
    private Long projectId;
    private String userId;
    private String actorId;
    private String agentId;
    private String sessionId;
    private String runId;
    private String delegationId;
    private String commandId;
    private String requestedPolicyVersion;
}
