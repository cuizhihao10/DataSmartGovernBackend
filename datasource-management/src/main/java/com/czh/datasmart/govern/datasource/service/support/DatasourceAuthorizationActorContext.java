/**
 * @Author : Cui
 * @Date: 2026/07/09 01:07
 * @Description DataSmart Govern Backend - DatasourceAuthorizationActorContext.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.support;

/**
 * 数据源授权场景中的操作者上下文。
 *
 * <p>gateway 会把 Keycloak/企业 IdP 解析后的身份注入为 Header，datasource-management 不应该重新解析 JWT，
 * 但需要把这些低敏身份字段用于授权匹配和审计落库。本 record 把 Header 归一化为领域对象，避免各个 Controller 方法重复传递散乱字符串。</p>
 */
public record DatasourceAuthorizationActorContext(String actorId,
                                                  String actorRole,
                                                  String actorType) {

    /**
     * 是否至少包含一个可用于匹配授权主体的身份字段。
     */
    public boolean hasIdentitySignal() {
        return hasText(actorId) || hasText(actorRole) || hasText(actorType);
    }

    /**
     * 判断当前请求是否来自服务账号。
     */
    public boolean isServiceAccount() {
        return "SERVICE_ACCOUNT".equalsIgnoreCase(trimToEmpty(actorType))
                || "SERVICE_ACCOUNT".equalsIgnoreCase(trimToEmpty(actorRole));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
