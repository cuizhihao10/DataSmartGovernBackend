/**
 * @Author : Cui
 * @Date: 2026/07/08 02:35
 * @Description DataSmart Govern Backend - DataSourceUsagePurpose.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.support;

import java.util.Locale;

/**
 * 数据源业务用途枚举。
 *
 * <p>这个枚举只表达“这条数据源登记在 DataSmart 产品中允许扮演什么业务角色”，不表达数据库类型，
 * 也不替代连接器能力探测。比如同样是 PostgreSQL，一条只读账号连接可以登记为 {@link #SOURCE}，
 * 一条数仓写入账号连接可以登记为 {@link #TARGET}。</p>
 *
 * <p>本枚举故意只保留 SOURCE 和 TARGET 两个值，不再提供 BOTH。原因是同步任务天然有源端和目标端两个
 * 不同职责：源端负责读取，目标端负责写入。真实商用环境里读账号和写账号通常需要分离管理，如果继续允许
 * “两端都可用”，前端就很容易把同一条连接同时放进两个选择器里，用户也更容易把生产源库误选为目标端写入库。</p>
 *
 * <p>历史版本曾经使用 BOTH 作为兼容默认值。为了避免已经落库的历史数据在服务启动或编辑时直接报错，
 * {@link #fromPersistedValueOrDefault(String)} 会把历史 BOTH 折叠为 SOURCE。这个选择偏保守：只读源端通常
 * 比目标端写入更安全；如果这条历史数据源确实要作为目标端使用，管理员应显式编辑为 TARGET，或者新建一条
 * 目标端专用数据源。</p>
 */
public enum DataSourceUsagePurpose {

    /**
     * 仅作为源端使用。
     *
     * <p>SOURCE 数据源只应该出现在“源端数据源”选择器中。典型场景包括生产业务库只读账号、客户授权查询库、
     * 外部 API 的读取型连接等。同步 worker 只能从这里抽取数据，不能把它当成目标库写入。</p>
     */
    SOURCE,

    /**
     * 仅作为目标端使用。
     *
     * <p>TARGET 数据源只应该出现在“目标端数据源”选择器中。典型场景包括 ODS/DWD 数仓层、临时落地库、
     * 迁移目标库或报表分析库。它通常具备 INSERT/UPDATE 权限，因此不应该被误当作源端读取入口。</p>
     */
    TARGET;

    /**
     * 将外部输入解析为严格的用户可配置用途。
     *
     * @param value 创建/更新/查询请求中传入的用途编码。
     * @return SOURCE 或 TARGET。
     * @throws IllegalArgumentException 当为空、为 BOTH 或其他未知值时抛出，阻止前端继续提交含糊配置。
     */
    public static DataSourceUsagePurpose fromValue(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("数据源用途不能为空，请选择 SOURCE（源端）或 TARGET（目标端）");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (DataSourceUsagePurpose purpose : values()) {
            if (purpose.name().equals(normalized)) {
                return purpose;
            }
        }
        throw new IllegalArgumentException("不支持的数据源用途: " + value + "，可选值仅为 SOURCE、TARGET");
    }

    /**
     * 解析已经持久化在数据库中的用途值。
     *
     * <p>这个方法只用于兼容旧库或迁移过程中的历史数据，不应该用于新建请求。旧值 BOTH 会被折叠成 SOURCE，
     * 这样可以确保旧数据不会继续出现在目标端候选列表里，避免写入侧误选风险。</p>
     *
     * @param value 数据库存量值。
     * @return 可继续参与业务判断的用途值。
     */
    public static DataSourceUsagePurpose fromPersistedValueOrDefault(String value) {
        if (value == null || value.isBlank() || "BOTH".equalsIgnoreCase(value.trim())) {
            return SOURCE;
        }
        return fromValue(value);
    }

    /**
     * 判断当前用途是否满足某个选择器角色。
     *
     * @param requiredRole 前端或调用方希望筛选的角色，通常为 SOURCE 或 TARGET；为空时表示不过滤。
     * @return true 表示当前数据源可以出现在该角色的候选列表中。
     */
    public boolean matchesRole(String requiredRole) {
        if (requiredRole == null || requiredRole.isBlank()) {
            return true;
        }
        return this == fromValue(requiredRole);
    }
}
