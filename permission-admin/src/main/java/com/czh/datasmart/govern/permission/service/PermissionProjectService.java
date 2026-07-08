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
import com.czh.datasmart.govern.permission.controller.dto.PermissionProjectDeletionCheckResponse;
import com.czh.datasmart.govern.permission.controller.dto.PermissionProjectMutationResult;
import com.czh.datasmart.govern.permission.controller.dto.PermissionProjectQueryCriteria;
import com.czh.datasmart.govern.permission.controller.dto.PermissionProjectStatusChangeRequest;
import com.czh.datasmart.govern.permission.controller.dto.PermissionProjectUpdateRequest;
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

    /**
     * 更新项目基础信息。
     *
     * <p>该动作只更新项目名称、编码、类型、负责人快照和描述，不隐式修改项目成员授权。
     * 如果需要真正把某个用户加入/移出项目，应继续使用项目成员管理接口，避免基础资料编辑绕过授权审计。</p>
     *
     * @param projectId 项目 ID。
     * @param request 更新请求。
     * @param actorContext 当前操作者上下文。
     * @return 更新结果。
     */
    PermissionProjectMutationResult updateProject(Long projectId,
                                                  PermissionProjectUpdateRequest request,
                                                  PermissionActorContext actorContext);

    /**
     * 启用项目。
     *
     * <p>启用只允许从 DISABLED 回到 ACTIVE。已归档项目代表项目已经退出日常使用，不通过普通启用入口恢复。</p>
     */
    PermissionProjectMutationResult activateProject(Long projectId,
                                                    PermissionProjectStatusChangeRequest request,
                                                    PermissionActorContext actorContext);

    /**
     * 禁用项目。
     *
     * <p>禁用表示暂停新资源创建和普通使用入口，但不物理删除历史数据，适合客户停用、风险排查和临时冻结场景。</p>
     */
    PermissionProjectMutationResult disableProject(Long projectId,
                                                   PermissionProjectStatusChangeRequest request,
                                                   PermissionActorContext actorContext);

    /**
     * 检查项目是否可以执行归档式删除。
     *
     * <p>只做只读检查，不改变项目状态，供前端删除确认弹窗和运营排障使用。</p>
     */
    PermissionProjectDeletionCheckResponse checkProjectDeletion(Long projectId,
                                                                PermissionActorContext actorContext);

    /**
     * 归档项目。
     *
     * <p>归档会从默认项目列表隐藏项目，并阻止后续新资源继续落入该项目。
     * 如果项目下仍存在活动数据源、启用模板或非归档任务，服务端会拒绝归档。</p>
     */
    PermissionProjectMutationResult archiveProject(Long projectId,
                                                   PermissionProjectStatusChangeRequest request,
                                                   PermissionActorContext actorContext);

    /**
     * 删除项目。
     *
     * <p>当前实现采用“归档式删除”，即把项目状态置为 ARCHIVED，而不是物理删除 permission_project 行。
     * 这样可以保留历史执行、审计、成员授权变更和 Agent 上下文的可追溯性。</p>
     */
    PermissionProjectMutationResult deleteProject(Long projectId,
                                                  PermissionProjectStatusChangeRequest request,
                                                  PermissionActorContext actorContext);
}
