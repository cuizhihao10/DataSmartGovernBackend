package com.czh.datasmart.govern.permission.service.support;

import java.util.List;
import java.util.Locale;

/**
 * @Author : Cui
 * @Date: 2026/05/06 00:18
 * @Description DataSmart Govern Backend - PermissionAdminSupport.java
 * @Version:1.0.0
 *
 * 权限管理通用支持工具。
 *
 * <p>这个类只保存 permission-admin 内部多个 support 都会使用的稳定约定：
 * 平台租户 ID、通用 HTTP 方法、编码归一化、租户范围归一化等。
 * 这些规则如果散落在多个服务里，后续补多租户隔离、平台级策略覆盖、缓存键设计时很容易出现不一致。
 *
 * <p>它不是通用工具包，而是权限域内的“小型领域常量/工具类”。
 * 因此放在 permission-admin 模块内部，避免过早扩散到 platform-common。
 */
public final class PermissionAdminSupport {

    /**
     * 平台级默认租户。
     *
     * <p>当前使用 0 表示平台公共策略，例如全局角色、全局菜单、全局路由策略。
     * 业务租户查询权限时通常需要同时读取“平台默认 + 当前租户自定义”两类策略。
     */
    public static final long PLATFORM_TENANT_ID = 0L;

    /**
     * 表示匹配任意 HTTP 方法的路由策略值。
     */
    public static final String ANY_HTTP_METHOD = "ANY";

    /**
     * 权限策略变更审计使用的资源类型。
     */
    public static final String RESOURCE_TYPE_SYSTEM_SETTING = "SYSTEM_SETTING";

    private PermissionAdminSupport() {
    }

    /**
     * 统一编码规范化。
     *
     * <p>角色、HTTP 方法、ALLOW/DENY 等字段都属于稳定枚举型编码，
     * 在写入数据库前转成大写可降低前端、脚本、初始化 SQL 之间的大小写差异。
     */
    public static String normalizeCode(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * 归一化租户 ID。
     *
     * <p>调用方未传 tenantId 时默认按平台租户处理，这让平台级策略查询和管理接口有稳定默认值。
     */
    public static Long normalizeTenantId(Long tenantId) {
        return tenantId == null ? PLATFORM_TENANT_ID : tenantId;
    }

    /**
     * 判断是否为平台租户。
     */
    public static boolean isPlatformTenant(Long tenantId) {
        return PLATFORM_TENANT_ID == normalizeTenantId(tenantId);
    }

    /**
     * 返回平台默认租户和当前租户两个策略范围。
     *
     * <p>如果当前本身就是平台租户，则只返回平台租户，避免重复 in 条件。
     */
    public static List<Long> platformAndTenantIds(Long tenantId) {
        Long normalizedTenantId = normalizeTenantId(tenantId);
        if (PLATFORM_TENANT_ID == normalizedTenantId) {
            return List.of(PLATFORM_TENANT_ID);
        }
        return List.of(PLATFORM_TENANT_ID, normalizedTenantId);
    }

    /**
     * 空值安全字符串。
     */
    public static String nullSafe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 为审计摘要拼接可选字段。
     */
    public static String valueForAudit(String prefix, String value) {
        return value == null || value.isBlank() ? "" : prefix + value.trim();
    }

    /**
     * JSON 字符串转义。
     *
     * <p>当前审计 detailJson 先维持轻量手写 JSON，后续如果审计字段继续增加，
     * 可以在 PermissionAuditSupport 中统一替换为 ObjectMapper。
     */
    public static String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
