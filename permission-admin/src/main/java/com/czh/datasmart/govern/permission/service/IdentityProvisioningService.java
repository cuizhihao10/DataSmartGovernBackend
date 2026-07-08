/**
 * @Author : Cui
 * @Date: 2026/07/05 03:26
 * @Description DataSmartGovernBackend - IdentityProvisioningService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service;

import com.czh.datasmart.govern.common.api.PlatformPageResponse;
import com.czh.datasmart.govern.permission.controller.dto.AuthorizationSubjectCandidateQueryCriteria;
import com.czh.datasmart.govern.permission.controller.dto.AuthorizationSubjectCandidateView;
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
     * 分页查询可用于业务资源授权的主体候选。
     *
     * <p>该方法是账号治理能力向业务授权场景提供的“低敏读模型”。
     * 它不创建账号、不修改项目成员关系，也不判断某个资源最终是否可被访问；
     * 它只负责把 permission-admin 已知的用户、角色以稳定、低敏、可分页的方式暴露给前端授权弹窗。
     * datasource-management 等业务服务继续负责自己的实例级 ACL 写入和资源访问校验。</p>
     */
    PlatformPageResponse<AuthorizationSubjectCandidateView> pageAuthorizationSubjectCandidates(
            AuthorizationSubjectCandidateQueryCriteria criteria,
            PermissionActorContext actorContext);

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
