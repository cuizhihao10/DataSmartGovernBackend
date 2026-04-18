package com.czh.datasmart.govern.datasource.support;

/**
 * @Author : Cui
 * @Date: 2026/4/18 21:55
 * @Description DataSmart Govern Backend - DataSourceStatus.java
 * @Version:1.0.0
 *
 * 数据源状态常量。
 * 当前阶段聚焦的是“这条配置是否允许被平台使用”：
 * - ACTIVE：已启用，可以被其他模块使用。
 * - INACTIVE：已停用，但配置仍然保留。
 * - DELETED：逻辑删除，不再参与常规业务。
 */
public final class DataSourceStatus {

    /**
     * 已启用。
     */
    public static final String ACTIVE = "ACTIVE";

    /**
     * 已停用。
     */
    public static final String INACTIVE = "INACTIVE";

    /**
     * 已逻辑删除。
     */
    public static final String DELETED = "DELETED";

    private DataSourceStatus() {
    }
}
