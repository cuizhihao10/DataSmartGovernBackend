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
 * gateway 会把外部请求解释成 HTTP 方法、路径、业务资源类型和业务动作，
 * permission-admin 再用这些字段判断当前角色是否可以访问。
 *
 * <p>这里保留 resourceType/action 两个可空字段，是为了兼容两类策略：
 * 1. 老的路径级策略：只配置 httpMethod + pathPattern，例如平台管理员允许 `/api/**`；
 * 2. 新的动作级策略：同时配置 resourceType + action，例如只允许服务账号执行 SYNC_EXECUTION + CALLBACK。
 * 可空并不代表字段不重要，而是代表该策略愿意作为“通配兜底策略”参与匹配。
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
     * 资源类型，例如 SYNC_TASK、SYNC_EXECUTION、TASK_OPERATION。
     *
     * <p>该字段把 URL 路由升级为业务语义。没有它时，`POST /api/sync/**` 可能同时代表创建同步任务、
     * 执行器回调、恢复过期租约或关闭事故；这些动作的风险完全不同，不能长期只靠路径判断。
     * 字段为空表示旧式路径级通配策略，会匹配任意资源类型。
     */
    private String resourceType;

    /**
     * 业务动作，例如 VIEW、CREATE、CALLBACK、RECOVER。
     *
     * <p>HTTP 方法只能表达技术语义，无法表达业务意图。
     * 例如 POST 可能是 CREATE，也可能是 CALLBACK、RECOVER、ACKNOWLEDGE。
     * 将 action 落表后，permission-admin 才能支持按钮级权限、高风险动作审批和更清晰的审计分类。
     * 字段为空表示旧式路径级通配策略，会匹配任意业务动作。
     */
    private String action;

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
