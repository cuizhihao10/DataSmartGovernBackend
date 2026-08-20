/**
 * @Author : Cui
 * @Date: 2026/08/21 10:00
 * @Description DataSmart Govern Backend - GraphFactApprovalRegisterRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 业务图事实审批登记请求。
 *
 * <p>它只保存控制面定位信息和事实包指纹，不接受实体正文、SQL、样本数据或模型原文。
 * 具体事实包通过 factBundleUri 由受控 worker 按 URI 加载，避免把大 payload 放进审批表和 Kafka。</p>
 */
@Data
public class GraphFactApprovalRegisterRequest {

    /** 服务端审批事实 ID。 */
    @NotBlank(message = "approvalFactId 不能为空")
    private String approvalFactId;

    /** 双主体和资源范围字段。 */
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
    private String policyVersion;
    private String status;
    private LocalDateTime expiresAt;
    private String approvedByActorId;
    private List<String> reasonCodes;
    private List<String> evidenceCodes;

    /** 已持久化的图事实包稳定 URI。 */
    @NotBlank(message = "factBundleUri 不能为空")
    private String factBundleUri;

    /** 事实内容的 SHA-256 指纹，不包含 approvalFactId。 */
    @NotBlank(message = "factFingerprint 不能为空")
    private String factFingerprint;

    /** 审批时看到的实体数量。 */
    @Min(value = 0, message = "entityCount 不能为负数")
    private int entityCount;

    /** 审批时看到的关系数量。 */
    @Min(value = 0, message = "edgeCount 不能为负数")
    private int edgeCount;
}
