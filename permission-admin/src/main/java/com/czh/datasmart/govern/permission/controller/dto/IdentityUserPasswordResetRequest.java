/**
 * @Author : Cui
 * @Date: 2026/07/05 03:26
 * @Description DataSmartGovernBackend - IdentityUserPasswordResetRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 重置 IdP 密码请求。
 *
 * <p>password 只在内存中短暂流转到 Keycloak/企业 IdP，不会保存到 DataSmart 数据库，不会出现在响应、
 * 审计 detailJson 或日志里。生产环境更推荐发送“重置密码邮件/一次性链接”，当前同步 reset-password
 * 是为了先完成本地 Keycloak 闭环。
 *
 * @param password 新密码，只转发给 IdP。
 * @param temporaryPassword 是否设置为临时密码；true 表示用户下次登录必须改密。
 * @param reason 重置原因，进入低敏审计。
 */
public record IdentityUserPasswordResetRequest(
        @NotBlank @Size(min = 8, max = 256) String password,
        Boolean temporaryPassword,
        @Size(max = 500) String reason) {
}
