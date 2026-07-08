/**
 * @Author : Cui
 * @Date: 2026/07/09 01:07
 * @Description DataSmart Govern Backend - DataSourceAuthorizationSubjectType.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.support;

import java.util.Locale;

/**
 * 数据源授权主体类型。
 *
 * <p>主体类型回答“这条授权授给谁”。真实企业系统里，资源授权通常不只授给单个用户：
 * 还可能授给某个项目角色、运维角色、服务账号或外部 IdP 组。当前先支持 USER/ROLE/SERVICE_ACCOUNT，
 * 既能覆盖页面上的“授权给其他用户”，也给后续服务账号自动执行、角色批量授权留下演进空间。</p>
 */
public enum DataSourceAuthorizationSubjectType {

    /**
     * 授权给单个用户，subjectId 通常对应 permission_identity_user.actor_id 或 Keycloak 用户映射 ID。
     */
    USER,

    /**
     * 授权给角色，subjectId 或 subjectRole 通常为 PROJECT_OWNER、OPERATOR、AUDITOR 等角色编码。
     */
    ROLE,

    /**
     * 授权给服务账号，用于 data-sync worker、质量扫描 worker、Agent 工具等机器身份。
     */
    SERVICE_ACCOUNT;

    /**
     * 解析外部提交的主体类型。
     */
    public static DataSourceAuthorizationSubjectType fromValue(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("数据源授权主体类型不能为空");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (DataSourceAuthorizationSubjectType type : values()) {
            if (type.name().equals(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("不支持的数据源授权主体类型: " + value + "，可选值为 USER、ROLE、SERVICE_ACCOUNT");
    }
}
