package com.czh.datasmart.govern.datasource.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * @Author : Cui
 * @Date: 2026/4/24 21:46
 * @Description DataSmart Govern Backend - SyncPermissionPolicyBinding.java
 * @Version:1.0.0
 *
 * 同步权限策略绑定实体。
 * 这张表是 datasource-management 向“真正可配置权限治理”迈进的第一步。
 *
 * 为什么需要把绑定关系单独落表，而不是继续只靠 application.yml：
 * 1. 配置文件更适合默认值和开发演示，不适合长期承载租户级、角色级的动态治理变更。
 * 2. 企业产品里，权限绑定往往需要被管理员调整、被审计追踪、被审批复核，数据库对象更容易承载这些诉求。
 * 3. 后续即使引入独立 permission-admin 模块，这张表和这套语义也能自然迁移或作为本地缓存层继续存在。
 *
 * 当前表的设计重点：
 * 1. 同时支持平台全局绑定和租户级覆盖。
 * 2. 支持多类绑定对象，而不是只做菜单绑定。
 * 3. 保留来源、优先级、备注和启停状态，便于后续做治理和审计。
 */
@Data
@TableName("sync_permission_policy_binding")
public class SyncPermissionPolicyBinding {

    /**
     * 绑定主键。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 绑定作用域所属租户。
     * 当前约定 `0` 表示平台级全局默认绑定，非 `0` 表示租户级覆盖绑定。
     * 这样做的原因是 MySQL 对 `NULL` 的唯一约束支持不够理想，使用 `0` 更容易稳定建立唯一键。
     */
    private Long tenantId;

    /**
     * 被绑定的角色枚举名。
     * 例如 `PROJECT_OWNER`、`TENANT_ADMINISTRATOR`。
     */
    private String actorRole;

    /**
     * 绑定类型。
     * 例如 `MENU`、`ROUTE`、`DATA_SCOPE`。
     */
    private String bindingType;

    /**
     * 绑定值。
     * 不同类型下的含义不同：
     * - `MENU` 时通常是菜单编码；
     * - `ROUTE` 时通常是 `SyncAdminRoutePolicy` 枚举名；
     * - `DATA_SCOPE` 时通常是 `OWNED/TENANT/PLATFORM`。
     */
    private String bindingValue;

    /**
     * 绑定来源。
     * 当前第一版主要使用 `MANUAL`，后续可扩展为 `BOOTSTRAP`、`IMPORT`、`SYNCED` 等。
     */
    private String bindingSource;

    /**
     * 是否启用当前绑定。
     * 这里保留软停用而不是物理删除，是为了让策略变更在数据库里留下可追踪轨迹。
     */
    private Boolean enabled;

    /**
     * 绑定优先级。
     * 数值越大，表示越希望在同层级候选中优先被解释。
     */
    private Integer priority;

    /**
     * 绑定说明。
     * 用于记录为什么新增/替换这条绑定，方便后续人工复盘。
     */
    private String note;

    /**
     * 创建人 ID。
     */
    private Long createdBy;

    /**
     * 更新人 ID。
     */
    private Long updatedBy;

    /**
     * 创建时间。
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新时间。
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
