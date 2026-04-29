/**
 * @Author : Cui
 * @Date: 2026/04/25 23:00
 * @Description DataSmart Govern Backend - PermissionRoutePolicy.java
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
 * 路由权限策略实体。
 *
 * <p>菜单决定“看不看得见入口”，路由策略决定“请求能不能真正进来”。
 * 后续 gateway 可以调用 permission-admin，基于 roleCode、HTTP 方法、路径模式和策略效果完成路由级授权。
 */
@Data
@TableName("permission_route_policy")
public class PermissionRoutePolicy {

    /**
     * 策略主键。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 租户 ID，0 表示平台全局默认策略。
     */
    private Long tenantId;

    /**
     * 策略名称。
     */
    private String policyName;

    /**
     * 角色编码。
     */
    private String roleCode;

    /**
     * HTTP 方法，例如 GET、POST、PUT、DELETE、ANY。
     */
    private String httpMethod;

    /**
     * 路径模式，例如 /api/task/**。
     */
    private String pathPattern;

    /**
     * 策略效果：ALLOW 或 DENY。
     */
    private String effect;

    /**
     * 优先级，数值越大越优先。
     *
     * <p>当多个策略命中时，先按优先级排序；同优先级时，DENY 应优先于 ALLOW。
     */
    private Integer priority;

    /**
     * 是否启用。
     */
    private Boolean enabled;

    /**
     * 策略说明。
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
