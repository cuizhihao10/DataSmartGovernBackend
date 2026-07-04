/**
 * @Author : Cui
 * @Date: 2026/07/05 03:26
 * @Description DataSmartGovernBackend - IdentityUserRegisterRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 身份供应创建用户请求。
 *
 * <p>该 DTO 面向 DataSmart 管理端，而不是面向匿名自助注册页。因此它要求调用方已经通过 gateway 认证和权限判定，
 * 并由租户管理员或平台管理员代表组织创建账号。请求中的 password 只会被转发给 IdP，permission-admin 不会持久化。
 *
 * @param username 登录用户名，写入 Keycloak/企业 IdP，同时作为本地影子身份检索字段。
 * @param email 邮箱，可为空；返回响应会脱敏，避免管理 API 意外扩散个人信息。
 * @param firstName 名，传给 IdP 用于用户资料展示。
 * @param lastName 姓，传给 IdP 用于用户资料展示。
 * @param password 初始密码，只用于调用 IdP 创建凭据；不会写入数据库、日志、审计和响应。
 * @param temporaryPassword 是否临时密码；true 表示用户首次登录后应修改密码。
 * @param tenantId 目标租户；租户管理员只能创建自己租户内账号，平台管理员可跨租户。
 * @param actorId DataSmart 内部主体 ID；为空时由 permission_identity_actor_id_seq 自动分配。
 * @param actorRole DataSmart 角色编码；为空默认 ORDINARY_USER。
 * @param actorType 主体类型；为空默认 USER，服务账号可传 SERVICE_ACCOUNT。
 * @param workspaceId 默认工作区；用于 Agent、工具、记忆和项目范围隔离。
 * @param enabled 是否在 IdP 中启用；为空默认 true。
 * @param emailVerified 是否标记邮箱已验证；本地创建一般默认 false。
 * @param reason 创建原因，写入低敏审计摘要，便于后续复盘账号来源。
 */
public record IdentityUserRegisterRequest(
        @NotBlank @Size(max = 128) String username,
        @Email @Size(max = 255) String email,
        @Size(max = 128) String firstName,
        @Size(max = 128) String lastName,
        @NotBlank @Size(min = 8, max = 256) String password,
        Boolean temporaryPassword,
        Long tenantId,
        Long actorId,
        @Size(max = 64) String actorRole,
        @Size(max = 64) String actorType,
        @Size(max = 128) String workspaceId,
        Boolean enabled,
        Boolean emailVerified,
        @Size(max = 500) String reason) {
}
