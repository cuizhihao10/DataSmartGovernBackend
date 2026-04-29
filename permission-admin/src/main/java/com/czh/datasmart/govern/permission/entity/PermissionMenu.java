/**
 * @Author : Cui
 * @Date: 2026/04/25 23:00
 * @Description DataSmart Govern Backend - PermissionMenu.java
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
 * 菜单资源实体。
 *
 * <p>菜单权限解决的是“用户登录后能看到哪些管理入口”。
 * 它不是最终安全边界，因为真正的安全还要看路由策略和数据范围；
 * 但菜单权限对商业产品非常重要，因为它决定后台管理界面的角色差异和功能可见性。
 */
@Data
@TableName("permission_menu")
public class PermissionMenu {

    /**
     * 菜单主键。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 菜单编码，作为角色绑定菜单时的稳定标识。
     */
    private String menuCode;

    /**
     * 父菜单编码，支持多级菜单树。
     */
    private String parentCode;

    /**
     * 菜单名称。
     */
    private String menuName;

    /**
     * 前端路由路径或逻辑路径。
     *
     * <p>后端不实现前端页面，但需要知道菜单大致指向哪个能力区域，便于权限矩阵和产品文档对齐。
     */
    private String path;

    /**
     * 菜单图标标识，预留给前端管理台使用。
     */
    private String icon;

    /**
     * 排序值，数值越小越靠前。
     */
    private Integer sortOrder;

    /**
     * 是否启用。
     */
    private Boolean enabled;

    /**
     * 菜单说明。
     */
    private String description;

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
