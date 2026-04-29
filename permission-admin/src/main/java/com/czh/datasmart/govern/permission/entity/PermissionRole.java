/**
 * @Author : Cui
 * @Date: 2026/04/25 23:00
 * @Description DataSmart Govern Backend - PermissionRole.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 平台角色实体。
 *
 * <p>角色是权限系统的第一层抽象。它不直接代表某个用户，而是代表一组权限能力的集合。
 * 在多租户商业产品里，同一个 roleCode 可以在不同 tenantId 下拥有不同菜单、路由和数据范围绑定。
 * 因此本表同时保留 tenantId 和 roleCode，便于支持“平台默认角色”和“租户级角色覆盖”。
 */
@Data
@TableName("permission_role")
public class PermissionRole {

    /**
     * 角色主键。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 租户 ID。
     *
     * <p>0 表示平台全局默认角色；非 0 表示租户内角色。
     * 后续做多租户时，可以先查租户角色，再回退到平台默认角色。
     */
    private Long tenantId;

    /**
     * 角色编码，例如 PLATFORM_ADMINISTRATOR、TENANT_ADMINISTRATOR、OPERATOR。
     */
    private String roleCode;

    /**
     * 角色名称，面向管理后台展示。
     */
    private String roleName;

    /**
     * 角色说明，解释该角色在产品里的职责边界。
     */
    private String description;

    /**
     * 是否为系统内置角色。
     *
     * <p>内置角色通常由初始化脚本创建，不建议普通租户管理员随意删除。
     */
    private Boolean systemRole;

    /**
     * 是否启用。
     *
     * <p>禁用角色后，后续权限判定应拒绝该角色继续获得菜单、路由或数据范围能力。
     */
    private Boolean enabled;

    /**
     * 创建人 ID。
     */
    private Long createdBy;

    /**
     * 最后更新人 ID。
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
