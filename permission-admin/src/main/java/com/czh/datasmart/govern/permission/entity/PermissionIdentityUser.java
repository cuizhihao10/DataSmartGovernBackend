/**
 * @Author : Cui
 * @Date: 2026/07/05 03:26
 * @Description DataSmartGovernBackend - PermissionIdentityUser.java
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
 * DataSmart 身份影子记录。
 *
 * <p>这张表不是登录账号密码表。真实密码、登录状态、刷新 Token、MFA、账号锁定等高敏能力全部归 Keycloak
 * 或企业 IdP 管理。DataSmart 保存这张“影子表”的目的，是把外部身份映射成平台内部可审计、可授权、可按租户隔离的 actor：
 * 1. providerUserId 对应 Keycloak/IdP 中真实用户 ID；
 * 2. actorId 是 DataSmart 内部业务、审计和权限系统使用的主体 ID；
 * 3. tenantId、actorRole、workspaceId 用于 gateway 与 permission-admin 做访问控制；
 * 4. status 用于表达 DataSmart 侧是否认为该身份仍可参与业务授权。
 */
@Data
@TableName("permission_identity_user")
public class PermissionIdentityUser {

    /**
     * 本地影子记录主键。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 租户 ID。
     *
     * <p>平台管理员可以创建跨租户账号；租户管理员只能创建自己租户内账号。该字段用于后续用户管理列表、
     * 审计查询、数据范围判定和用户画像隔离。
     */
    private Long tenantId;

    /**
     * DataSmart 内部 actorId。
     *
     * <p>actorId 会进入 gateway 注入的 X-DataSmart-Actor-Id，也会进入业务审计、Agent 记忆责任链、
     * 工具调用授权和项目成员授权。它不等同于 Keycloak 用户 ID，因为后者属于外部身份系统。
     */
    private Long actorId;

    /**
     * 身份供应模式。
     *
     * <p>当前为 KEYCLOAK_ADMIN_API；未来如果客户使用企业 IdP、SCIM 或 LDAP 桥接，该字段能区分用户来源。
     */
    private String providerMode;

    /**
     * 外部 IdP 用户 ID。
     *
     * <p>Keycloak 下通常是 UUID。后续禁用账号、重置密码、查询审计都应通过它定位外部用户。
     */
    private String providerUserId;

    /**
     * 登录用户名。
     *
     * <p>用户名用于 IdP 登录和后台检索，但不能作为唯一授权主体使用；真正的授权主体仍以 actorId/providerUserId 为准。
     */
    private String username;

    /**
     * 邮箱。
     *
     * <p>邮箱属于个人信息，返回给前端时应按场景做脱敏；当前服务响应默认只返回脱敏邮箱。
     */
    private String email;

    /**
     * DataSmart 角色编码。
     *
     * <p>该字段与 PermissionRoleCode 保持一致，例如 ORDINARY_USER、TENANT_ADMINISTRATOR、PLATFORM_ADMINISTRATOR。
     */
    private String actorRole;

    /**
     * 主体类型。
     *
     * <p>常见值是 USER 或 SERVICE_ACCOUNT。人类用户与机器账号必须分开审计，避免服务账号被误认为真人操作。
     */
    private String actorType;

    /**
     * 默认 workspace。
     *
     * <p>Agent、工具、记忆、RAG、项目成员等能力都会使用 workspace 作为隔离边界。为空表示暂不绑定默认 workspace。
     */
    private String workspaceId;

    /**
     * 本地影子身份状态。
     *
     * <p>ACTIVE 表示本地认为账号可用；DISABLED 表示已经通过 IdP 禁用或在 DataSmart 侧不再允许参与业务。
     */
    private String status;

    /**
     * 禁用原因。
     *
     * <p>禁用不是物理删除，原因可以帮助审计员理解该账号为什么停止使用，例如员工离职、临时冻结、租户退订等。
     */
    private String disabledReason;

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
