/**
 * @Author : Cui
 * @Date: 2026/04/25 23:00
 * @Description DataSmart Govern Backend - PermissionDataScopePolicy.java
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
 * 数据范围策略实体。
 *
 * <p>数据范围策略解决“允许访问哪些数据”的问题。
 * 例如普通用户只能看自己创建的数据源，租户管理员能看本租户所有数据源，平台管理员能看全平台资源。
 * 这类策略最终会被 datasource-management、task-management、data-quality 等模块消费。
 */
@Data
@TableName("permission_data_scope_policy")
public class PermissionDataScopePolicy {

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
     * 角色编码。
     */
    private String roleCode;

    /**
     * 资源类型，例如 DATASOURCE、SYNC_TASK、QUALITY_RULE。
     */
    private String resourceType;

    /**
     * 数据范围级别：SELF、PROJECT、TENANT、PLATFORM。
     */
    private String scopeLevel;

    /**
     * 范围表达式。
     *
     * <p>当前先保留字符串表达式，后续可以演进为 JSON DSL。
     * 例如 owner_id = ${actorId}、tenant_id = ${tenantId}、project_id in (...)。
     */
    private String scopeExpression;

    /**
     * 是否需要审批。
     *
     * <p>某些角色即使拥有访问能力，但涉及导出、敏感数据、跨租户分析时仍然可能需要审批。
     */
    private Boolean approvalRequired;

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
