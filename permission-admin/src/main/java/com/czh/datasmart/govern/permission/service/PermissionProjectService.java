/**
 * @Author : Cui
 * @Date: 2026/07/08 23:26
 * @Description DataSmart Govern Backend - PermissionProjectService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service;

import com.czh.datasmart.govern.common.api.PlatformPageResponse;
import com.czh.datasmart.govern.permission.controller.dto.PermissionActorContext;
import com.czh.datasmart.govern.permission.controller.dto.PermissionProjectCreateRequest;
import com.czh.datasmart.govern.permission.controller.dto.PermissionProjectMutationResult;
import com.czh.datasmart.govern.permission.controller.dto.PermissionProjectQueryCriteria;
import com.czh.datasmart.govern.permission.controller.dto.PermissionProjectView;

/**
 * 项目主数据服务。
 *
 * <p>该服务把“项目”从初始化脚本中的静态事实升级为可运营控制面能力。
 * 去掉用户可见 workspace 后，项目必须承接所有业务资源的归属、切换和授权入口：</p>
 *
 * <p>1. 数据源创建时使用当前项目；</p>
 * <p>2. 同步模板/任务创建时使用当前项目；</p>
 * <p>3. Agent 会话、记忆和工具预算后续也应绑定项目上下文；</p>
 * <p>4. permission_project_membership 负责把 actor 能访问哪些项目物化给 gateway 和业务服务。</p>
 */
public interface PermissionProjectService {

    /**
     * 分页查询当前操作者可见项目。
     *
     * @param criteria 查询条件。
     * @param actorContext 当前操作者上下文。
     * @return 项目分页视图，已隐藏 workspace 兼容字段。
     */
    PlatformPageResponse<PermissionProjectView> pageProjects(PermissionProjectQueryCriteria criteria,
                                                             PermissionActorContext actorContext);

    /**
     * 查询项目详情。
     *
     * @param projectId 项目 ID。
     * @param actorContext 当前操作者上下文。
     * @return 项目视图。
     */
    PermissionProjectView getProject(Long projectId, PermissionActorContext actorContext);

    /**
     * 创建项目，并自动给负责人授予 OWNER 项目成员关系。
     *
     * @param request 创建请求。
     * @param actorContext 当前操作者上下文。
     * @return 创建结果。
     */
    PermissionProjectMutationResult createProject(PermissionProjectCreateRequest request,
                                                  PermissionActorContext actorContext);
}
