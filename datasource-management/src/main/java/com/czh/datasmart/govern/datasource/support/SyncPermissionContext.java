package com.czh.datasmart.govern.datasource.support;

import lombok.Builder;
import lombok.Getter;

/**
 * @Author : Cui
 * @Date: 2026/4/20 22:12
 * @Description DataSmart Govern Backend - SyncPermissionContext.java
 * @Version:1.0.0
 *
 * 同步领域权限上下文。
 * 这个对象的职责，是把“谁在什么租户范围里，正在操作哪个治理对象”这一整组信息显式承载下来。
 *
 * 之所以要在当前阶段就补这个上下文，而不是继续只传 `actorRole`，是因为：
 * 1. `permission-admin-prd` 明确要求后续支持租户范围、数据范围、动作级权限和管理面控制；
 * 2. 如果业务层只知道角色名，不知道操作者属于哪个租户、目标对象属于哪个租户，就无法做最基本的租户隔离；
 * 3. 后续即使接入统一权限中心，业务层依然需要有一个稳定的“权限判定输入模型”，否则仍然会在各处散落上下文拼装逻辑。
 *
 * 当前版本重点覆盖三类范围信息：
 * - 操作者范围：actorId / actorRole / actorTenantId；
 * - 资源归属范围：resourceTenantId；
 * - 数据拥有范围：resourceOwnerId / resourceCreatedBy。
 *
 * 这样可以让本地权限评估器先支持：
 * - 跨租户禁止；
 * - 租户内治理允许；
 * - 负责人仅能操作自己拥有对象；
 * - 平台管理员拥有全局治理范围。
 */
@Getter
@Builder
public class SyncPermissionContext {

    /**
     * 当前操作者 ID。
     */
    private final Long actorId;

    /**
     * 当前操作者角色。
     */
    private final String actorRole;

    /**
     * 当前操作者所属租户。
     */
    private final Long actorTenantId;

    /**
     * 被操作资源所属租户。
     * 对于任务、模板、告警等租户级对象，这个字段通常不应为空。
     * 对于平台级观测或尚未完成租户归属建模的资源，可以暂时为空。
     */
    private final Long resourceTenantId;

    /**
     * 资源负责人。
     * 当前主要用于“负责人只能操作自己拥有的任务”这类 owned-action 判定。
     */
    private final Long resourceOwnerId;

    /**
     * 资源创建人。
     * 某些场景下对象虽然负责人为空，但创建人仍然可以作为本地最小数据范围判定依据。
     */
    private final Long resourceCreatedBy;
}
