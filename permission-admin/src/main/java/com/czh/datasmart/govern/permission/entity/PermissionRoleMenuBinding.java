/**
 * @Author : Cui
 * @Date: 2026/04/25 23:00
 * @Description DataSmart Govern Backend - PermissionRoleMenuBinding.java
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
 * 角色菜单绑定实体。
 *
 * <p>该表表达“某个租户范围内，某个角色可以看到哪些菜单”。
 * 之所以不把 roleCode 直接写进菜单表，是因为同一个菜单可能被多个角色复用，
 * 同一个角色在不同租户也可能拥有不同菜单集合。
 */
@Data
@TableName("permission_role_menu_binding")
public class PermissionRoleMenuBinding {

    /**
     * 绑定主键。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 租户 ID，0 表示平台全局默认绑定。
     */
    private Long tenantId;

    /**
     * 角色编码。
     */
    private String roleCode;

    /**
     * 菜单编码。
     */
    private String menuCode;

    /**
     * 是否启用。
     */
    private Boolean enabled;

    /**
     * 绑定来源，例如 BOOTSTRAP、MANUAL、IMPORT。
     */
    private String bindingSource;

    /**
     * 绑定说明或变更原因。
     */
    private String note;

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
