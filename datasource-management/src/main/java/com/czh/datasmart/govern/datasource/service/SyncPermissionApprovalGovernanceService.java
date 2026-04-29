package com.czh.datasmart.govern.datasource.service;

import com.czh.datasmart.govern.datasource.entity.SyncPermissionPolicyChangeRequest;
import com.czh.datasmart.govern.datasource.support.SyncPermissionApprovalDecision;

import java.util.List;

/**
 * @Author : Cui
 * @Date: 2026/4/24 23:18
 * @Description DataSmart Govern Backend - SyncPermissionApprovalGovernanceService.java
 * @Version:1.0.0
 *
 * 权限审批治理服务。
 * 这一层专门负责解释审批矩阵、审批快照、防自审和委托代批规则，
 * 避免这些规则散落在控制器或申请单服务实现里。
 */
public interface SyncPermissionApprovalGovernanceService {

    List<String> resolveRequiredApproverRoles(Long targetTenantId, String bindingType, String requesterRole);

    SyncPermissionApprovalDecision assertCanApprove(SyncPermissionPolicyChangeRequest entity,
                                                    Long actorId,
                                                    String actorRole,
                                                    Long actorTenantId);
}
