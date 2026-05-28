/**
 * @Author : Cui
 * @Date: 2026/05/05 23:40
 * @Description DataSmart Govern Backend - SyncAlertPermissionSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.support;

import com.czh.datasmart.govern.datasource.controller.dto.SyncActionRequest;
import com.czh.datasmart.govern.datasource.entity.SyncGovernanceAlert;
import com.czh.datasmart.govern.datasource.support.ActorRole;
import com.czh.datasmart.govern.datasource.support.SyncPermissionAction;
import com.czh.datasmart.govern.datasource.support.SyncPermissionContext;
import com.czh.datasmart.govern.datasource.support.SyncPermissionEvaluator;
import com.czh.datasmart.govern.datasource.support.SyncPermissionResource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 同步治理告警权限支持组件。
 *
 * <p>治理告警通常会暴露任务失败、数据源不可用、队列积压、投递死信等运营信息。
 * 这些信息不是普通公开列表，必须遵守租户边界和角色能力边界：
 * 平台管理员可以看全平台或指定租户，租户管理员、运营人员、审计人员通常只能看自己租户。</p>
 *
 * <p>把权限逻辑独立出来，是为了避免 `SyncGovernanceAlertServiceImpl` 在每个接口里重复拼装
 * `SyncPermissionContext`，也方便后续把“角色 + 租户 + 告警类型 + 严重级别 + 数据源归属”
 * 扩展为更细粒度的商业化策略。</p>
 */
@Component
@RequiredArgsConstructor
public class SyncAlertPermissionSupport {

    /**
     * datasource-management 内的同步权限评估器。
     *
     * <p>当前阶段仍沿用模块内轻量策略；未来可以平滑替换为 permission-admin 的实时策略客户端。</p>
     */
    private final SyncPermissionEvaluator syncPermissionEvaluator;

    /**
     * 解析当前请求实际允许查询的租户范围。
     *
     * @param actorRole 操作人角色。
     * @param actorTenantId 操作人所属租户。
     * @param requestedTenantId 请求中指定的目标租户。
     * @return 平台管理员可返回指定租户或 null 表示全平台；普通角色返回自身租户。
     */
    public Long resolveTenantQueryScope(String actorRole, Long actorTenantId, Long requestedTenantId) {
        ActorRole role = ActorRole.fromValue(actorRole);
        if (role == ActorRole.PLATFORM_ADMINISTRATOR) {
            return requestedTenantId;
        }
        if (requestedTenantId == null) {
            return actorTenantId;
        }
        if (actorTenantId != null && !actorTenantId.equals(requestedTenantId)) {
            throw new IllegalStateException("当前角色不能跨租户查看或操作同步治理告警");
        }
        return requestedTenantId;
    }

    /**
     * 校验告警列表、健康快照等租户级查询权限。
     */
    public void assertTenantAlertPermission(Long actorId,
                                            String actorRole,
                                            Long actorTenantId,
                                            Long resourceTenantId,
                                            SyncPermissionAction action) {
        syncPermissionEvaluator.assertAllowed(SyncPermissionContext.builder()
                        .actorId(actorId)
                        .actorRole(actorRole)
                        .actorTenantId(actorTenantId)
                        .resourceTenantId(resourceTenantId)
                        .build(),
                SyncPermissionResource.SYNC_ALERT, action);
    }

    /**
     * 校验单条告警动作权限。
     *
     * <p>确认、解决、手动投递、死信重入队都属于对告警对象的治理动作，
     * 因此必须使用告警自身的 tenantId 作为资源边界，而不是信任请求入参。</p>
     */
    public void assertAlertPermission(SyncGovernanceAlert alert,
                                      SyncActionRequest request,
                                      SyncPermissionResource resource,
                                      SyncPermissionAction action) {
        syncPermissionEvaluator.assertAllowed(buildAlertPermissionContext(alert, request),
                resource, action);
    }

    /**
     * 校验单条告警只读详情权限，例如查看投递记录。
     */
    public void assertAlertPermission(SyncGovernanceAlert alert,
                                      Long actorId,
                                      String actorRole,
                                      Long actorTenantId,
                                      SyncPermissionResource resource,
                                      SyncPermissionAction action) {
        syncPermissionEvaluator.assertAllowed(buildAlertPermissionContext(alert, actorId, actorRole, actorTenantId),
                resource, action);
    }

    private SyncPermissionContext buildAlertPermissionContext(SyncGovernanceAlert alert, SyncActionRequest request) {
        return buildAlertPermissionContext(alert, request.getActorId(), request.getActorRole(), request.getActorTenantId());
    }

    private SyncPermissionContext buildAlertPermissionContext(SyncGovernanceAlert alert,
                                                             Long actorId,
                                                             String actorRole,
                                                             Long actorTenantId) {
        return SyncPermissionContext.builder()
                .actorId(actorId)
                .actorRole(actorRole)
                .actorTenantId(actorTenantId)
                .resourceTenantId(alert.getTenantId())
                .build();
    }
}
