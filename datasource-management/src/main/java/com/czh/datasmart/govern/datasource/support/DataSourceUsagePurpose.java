/**
 * @Author : Cui
 * @Date: 2026/07/08 02:35
 * @Description DataSmart Govern Backend - DataSourceUsagePurpose.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.support;

import java.util.Locale;

/**
 * 数据源用途枚举。
 *
 * <p>这个枚举解决的是“同一个连接配置在产品里应该出现在哪些选择器中”的问题。
 * 数据同步任务天然有源端和目标端两个角色：源端负责读取，目标端负责写入。很多真实客户环境里，
 * 读库账号和写库账号会分开管理，甚至源端数据库只允许 SELECT，目标端数据库只允许 INSERT/UPDATE。
 * 如果后端没有用途字段，前端只能把所有数据源都放进两个下拉框，用户就可能把只读源库误选为目标端。</p>
 *
 * <p>注意：用途不是数据库类型，也不是连接能力画像。MYSQL/POSTGRESQL 描述“它是什么系统”，
 * SOURCE/TARGET/BOTH 描述“这个连接在当前平台里被允许扮演什么业务角色”。后续即使接入更细粒度的
 * connector capability、凭据权限探测或数据源授权策略，本字段仍然可以作为最直观的产品筛选入口。</p>
 */
public enum DataSourceUsagePurpose {

    /**
     * 仅作为源端使用。
     *
     * <p>适合只读账号、生产业务库、外部客户授权查询库等场景。新建同步任务时，这类数据源应该出现在
     * “源端数据源”选择器中，但不应该出现在“目标端数据源”选择器中。</p>
     */
    SOURCE,

    /**
     * 仅作为目标端使用。
     *
     * <p>适合数据仓库 ODS/DWD 层、临时落地库、分析库或迁移目标库。它通常需要写入权限，
     * 因此不应被误当作源端读取入口，除非管理员明确把用途调整为 BOTH。</p>
     */
    TARGET,

    /**
     * 同时允许作为源端和目标端。
     *
     * <p>这是为了兼容历史数据和本地 E2E 场景的默认值。生产环境里如果读写权限能够明确拆开，
     * 更推荐分别登记 SOURCE 和 TARGET 两类数据源，让用户选择时更安全。</p>
     */
    BOTH;

    /**
     * 将外部输入归一化为枚举值。
     *
     * @param value 请求体、查询参数或旧数据中的用途值。
     * @return 归一化后的用途；空值默认 BOTH，保证旧版本数据源仍可同时用于源端和目标端。
     */
    public static DataSourceUsagePurpose fromValue(String value) {
        if (value == null || value.isBlank()) {
            return BOTH;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (DataSourceUsagePurpose purpose : values()) {
            if (purpose.name().equals(normalized)) {
                return purpose;
            }
        }
        throw new IllegalArgumentException("不支持的数据源用途: " + value + "，可选值为 SOURCE、TARGET、BOTH");
    }

    /**
     * 判断当前用途是否能满足某个选择器角色。
     *
     * @param requiredRole 前端或调用方希望筛选的角色，通常为 SOURCE 或 TARGET。
     * @return true 表示当前数据源可以出现在该角色的候选列表中。
     */
    public boolean matchesRole(String requiredRole) {
        if (requiredRole == null || requiredRole.isBlank()) {
            return true;
        }
        DataSourceUsagePurpose required = fromValue(requiredRole);
        return this == BOTH || required == BOTH || this == required;
    }
}
