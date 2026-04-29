package com.czh.datasmart.govern.datasource.controller.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @Author : Cui
 * @Date: 2026/4/24 23:18
 * @Description DataSmart Govern Backend - SyncPermissionPolicyChangeRequestView.java
 * @Version:1.0.0
 *
 * 权限绑定变更申请视图对象。
 * 这一层比数据库实体更偏向治理后台可读性，重点把审批链路中的关键解释信息显式返回给前端：
 * - 这单申请要求哪些角色审批；
 * - 最终是直接审批还是委托代批；
 * - 如果是委托代批，委托来源是谁。
 */
@Data
public class SyncPermissionPolicyChangeRequestView {

    private Long id;
    private Long targetTenantId;
    private String targetScopeType;
    private Long requesterId;
    private String requesterRole;
    private Long requesterTenantId;
    private String targetRole;
    private String bindingType;
    private List<String> bindingValues;
    private Integer requestedPriority;
    private String requestedBindingSource;
    private String requestReason;
    private List<String> requiredApproverRoles;
    private String requestStatus;
    private Long approverId;
    private String approverRole;
    private String approvalMode;
    private Long delegatedFromApproverId;
    private String delegatedFromApproverRole;
    private String approvalComment;
    private LocalDateTime approvedAt;
    private LocalDateTime executedAt;
    private String executionSummary;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
