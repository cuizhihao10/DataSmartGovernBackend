package com.czh.datasmart.govern.datasource.controller.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * @Author : Cui
 * @Date: 2026/4/24 23:18
 * @Description DataSmart Govern Backend - SyncPermissionApprovalDelegateRuleView.java
 * @Version:1.0.0
 *
 * 权限审批委托规则视图对象。
 * 主要用于给治理后台展示当前有哪些委托关系处于生效或历史状态。
 */
@Data
public class SyncPermissionApprovalDelegateRuleView {

    private Long id;
    private Long targetTenantId;
    private String targetScopeType;
    private Long delegatorId;
    private String delegatorRole;
    private Long delegateId;
    private String delegateRole;
    private LocalDateTime effectiveFrom;
    private LocalDateTime effectiveTo;
    private Boolean enabled;
    private Boolean activeNow;
    private String delegateReason;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
