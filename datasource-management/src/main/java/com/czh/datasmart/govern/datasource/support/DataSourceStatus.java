package com.czh.datasmart.govern.datasource.support;

/**
 * 数据源状态常量。
 * <p>
 * 数据源模块里的状态比任务模块简单一些，当前先聚焦“配置记录是否可用”：
 * - ACTIVE：已启用，可以被其他模块使用。
 * - INACTIVE：已停用，但配置仍保留。
 * - DELETED：逻辑删除，配置不再参与正常业务。
 */
public final class DataSourceStatus {

    public static final String ACTIVE = "ACTIVE";

    public static final String INACTIVE = "INACTIVE";

    public static final String DELETED = "DELETED";

    private DataSourceStatus() {
    }
}
