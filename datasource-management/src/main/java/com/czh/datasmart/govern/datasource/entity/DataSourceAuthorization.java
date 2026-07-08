/**
 * @Author : Cui
 * @Date: 2026/07/09 01:07
 * @Description DataSmart Govern Backend - DataSourceAuthorization.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据源实例级授权实体。
 *
 * <p>这张表是 datasource-management 内部的资源 ACL 账本，用于保存“某个数据源授权给某个用户/角色/服务账号”的事实。
 * 它不同于 permission-admin 的路由权限：路由权限回答“某类角色是否允许访问某个 API”，本实体回答“某个具体数据源实例是否允许被某个主体使用”。
 * 两者结合后，系统才能同时做到按钮级权限和资源实例级权限。</p>
 */
@Data
@TableName("datasource_authorization")
public class DataSourceAuthorization {

    /**
     * 授权记录主键。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 被授权的数据源 ID。
     */
    private Long datasourceId;

    /**
     * 数据源所属租户快照，用于租户级查询、审计和授权隔离。
     */
    private Long tenantId;

    /**
     * 数据源所属项目快照，用于项目级授权列表和可见性过滤。
     */
    private Long projectId;

    /**
     * 历史兼容字段。当前用户可见产品层级已收敛到项目，但保留 workspaceId 有利于兼容旧数据和未来内部沙箱语义。
     */
    private Long workspaceId;

    /**
     * 授权主体类型：USER、ROLE 或 SERVICE_ACCOUNT。
     */
    private String subjectType;

    /**
     * 授权主体 ID。
     *
     * <p>USER 场景通常是 actorId；ROLE 场景可以直接保存角色编码；SERVICE_ACCOUNT 场景保存服务账号 ID。
     * 这里使用字符串而不是 Long，是为了兼容企业 IdP、Keycloak、服务账号和外部组织系统中可能出现的非数字标识。</p>
     */
    private String subjectId;

    /**
     * 授权主体展示名称，用于页面展示和审计阅读。
     */
    private String subjectName;

    /**
     * 授权主体角色快照。USER 授权时可记录该用户当时的角色，ROLE 授权时可直接等于角色编码。
     */
    private String subjectRole;

    /**
     * 授权动作集合，使用逗号分隔的 VIEW、USE、MANAGE。
     */
    private String authorizedActions;

    /**
     * 授权来源说明，例如 UI_MANUAL、IMPORT、SYSTEM_BOOTSTRAP、AGENT_RECOMMENDATION。
     */
    private String grantSource;

    /**
     * 当前授权状态：ACTIVE 或 REVOKED。
     */
    private String status;

    /**
     * 授权原因。该字段便于审计员理解为什么一个用户被授予数据源访问权。
     */
    private String grantReason;

    /**
     * 授权过期时间。为空表示长期有效；生产环境更建议对临时排障授权设置过期时间。
     */
    private LocalDateTime expireTime;

    /**
     * 授权人 actorId。
     */
    private String grantedByActorId;

    /**
     * 授权人角色快照。
     */
    private String grantedByActorRole;

    /**
     * 授权生效时间。
     */
    private LocalDateTime grantedTime;

    /**
     * 撤销授权的 actorId。
     */
    private String revokedByActorId;

    /**
     * 撤销授权的角色快照。
     */
    private String revokedByActorRole;

    /**
     * 撤销原因。
     */
    private String revokeReason;

    /**
     * 撤销时间。
     */
    private LocalDateTime revokedTime;

    /**
     * 记录创建时间。
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 记录更新时间。
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
