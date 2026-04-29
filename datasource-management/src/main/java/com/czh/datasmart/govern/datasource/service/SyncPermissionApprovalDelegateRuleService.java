package com.czh.datasmart.govern.datasource.service;

import com.czh.datasmart.govern.datasource.controller.dto.CreateSyncPermissionApprovalDelegateRuleRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncPermissionApprovalDelegateRuleView;
import com.czh.datasmart.govern.datasource.controller.dto.SyncActionRequest;
import com.czh.datasmart.govern.datasource.entity.SyncPermissionApprovalDelegateRule;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @Author : Cui
 * @Date: 2026/4/24 23:18
 * @Description DataSmart Govern Backend - SyncPermissionApprovalDelegateRuleService.java
 * @Version:1.0.0
 *
 * 权限审批委托规则服务。
 * 负责治理侧的委托规则创建、查询、禁用，以及审批决策阶段所需的有效规则检索。
 */
public interface SyncPermissionApprovalDelegateRuleService {

    SyncPermissionApprovalDelegateRuleView createDelegateRule(CreateSyncPermissionApprovalDelegateRuleRequest request);

    List<SyncPermissionApprovalDelegateRuleView> listDelegateRules(Long actorId,
                                                                  String actorRole,
                                                                  Long actorTenantId,
                                                                  Long targetTenantId,
                                                                  Long delegatorId,
                                                                  Long delegateId,
                                                                  Boolean activeOnly);

    SyncPermissionApprovalDelegateRuleView disableDelegateRule(Long ruleId, SyncActionRequest request);

    List<SyncPermissionApprovalDelegateRule> findEffectiveDelegateRules(Long targetTenantId,
                                                                       Long delegateId,
                                                                       String delegateRole,
                                                                       LocalDateTime decisionTime);
}
