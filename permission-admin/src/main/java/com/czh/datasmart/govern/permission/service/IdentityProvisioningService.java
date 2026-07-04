/**
 * @Author : Cui
 * @Date: 2026/07/05 03:26
 * @Description DataSmartGovernBackend - IdentityProvisioningService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service;

import com.czh.datasmart.govern.permission.controller.dto.IdentityProvisioningCapabilityView;
import com.czh.datasmart.govern.permission.controller.dto.IdentityUserDisableRequest;
import com.czh.datasmart.govern.permission.controller.dto.IdentityUserPasswordResetRequest;
import com.czh.datasmart.govern.permission.controller.dto.IdentityUserProvisionResult;
import com.czh.datasmart.govern.permission.controller.dto.IdentityUserRegisterRequest;
import com.czh.datasmart.govern.permission.controller.dto.PermissionActorContext;

/**
 * 身份供应服务接口。
 *
 * <p>接口层只暴露业务动作，不暴露 Keycloak Admin API 细节。这样 controller、gateway、测试和后续管理后台
 * 都不需要知道当前 IdP 是 Keycloak、企业 IdP 还是 SCIM 服务。
 */
public interface IdentityProvisioningService {

    /**
     * 查询当前身份供应能力。
     */
    IdentityProvisioningCapabilityView capabilities();

    /**
     * 创建 IdP 用户并写入 DataSmart 影子身份。
     */
    IdentityUserProvisionResult registerUser(IdentityUserRegisterRequest request, PermissionActorContext actorContext);

    /**
     * 禁用外部 IdP 用户，并同步禁用本地影子身份。
     */
    IdentityUserProvisionResult disableUser(String providerUserId,
                                            IdentityUserDisableRequest request,
                                            PermissionActorContext actorContext);

    /**
     * 重置外部 IdP 用户密码。
     */
    IdentityUserProvisionResult resetPassword(String providerUserId,
                                              IdentityUserPasswordResetRequest request,
                                              PermissionActorContext actorContext);
}
