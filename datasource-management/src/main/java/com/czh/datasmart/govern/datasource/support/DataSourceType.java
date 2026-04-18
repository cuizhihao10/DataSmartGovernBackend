package com.czh.datasmart.govern.datasource.support;

import java.util.Arrays;

/**
 * 数据源类型枚举。
 * <p>
 * 当前先支持 MySQL、PostgreSQL、SQL Server 三种 JDBC 数据源，
 * 因为这个模块的 pom 已经具备对应驱动，也是最适合先做通的第一批异构数据源。
 */
public enum DataSourceType {
    MYSQL("com.mysql.cj.jdbc.Driver"),
    POSTGRESQL("org.postgresql.Driver"),
    SQLSERVER("com.microsoft.sqlserver.jdbc.SQLServerDriver");

    private final String driverClassName;

    DataSourceType(String driverClassName) {
        this.driverClassName = driverClassName;
    }

    public String getDriverClassName() {
        return driverClassName;
    }

    /**
     * 对外部传入的类型值做归一化。
     */
    public static DataSourceType fromValue(String value) {
        return Arrays.stream(values())
                .filter(item -> item.name().equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unsupported datasource type: " + value));
    }
}
