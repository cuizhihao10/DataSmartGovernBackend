package com.czh.datasmart.govern.datasource.service;

import com.czh.datasmart.govern.datasource.controller.dto.ApproveSyncPermissionPolicyChangeRequest;
import com.czh.datasmart.govern.datasource.controller.dto.CreateSyncPermissionPolicyChangeRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncPermissionPolicyChangeRequestView;

import java.util.List;

/**
 * @Author : Cui
 * @Date: 2026/4/24 22:03
 * @Description DataSmart Govern Backend - SyncPermissionPolicyChangeRequestService.java
 * @Version:1.0.0
 *
 * 权限绑定变更申请服务。
 * 负责承载“提交申请 -> 查询申请 -> 审批并执行”的最小闭环。
 */
public interface SyncPermissionPolicyChangeRequestService {

    SyncPermissionPolicyChangeRequestView submitChangeRequest(CreateSyncPermissionPolicyChangeRequest request);

    List<SyncPermissionPolicyChangeRequestView> listChangeRequests(Long actorId,
                                                                   String actorRole,
                                                                   Long actorTenantId,
                                                                   Long targetTenantId,
                                                                   String targetRole,
                                                                   String bindingType,
                                                                   String requestStatus);

    SyncPermissionPolicyChangeRequestView approveChangeRequest(Long requestId,
                                                               ApproveSyncPermissionPolicyChangeRequest request);
}
