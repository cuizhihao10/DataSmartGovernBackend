/**
 * @Author : Cui
 * @Date: 2026/06/20 03:42
 * @Description DataSmart Govern Backend - SyncJdbcConnectionProvider.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.execution.jdbc;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 同步执行 JDBC 连接提供者。
 *
 * <p>真实数据同步 worker 需要打开源端和目标端数据库连接。
 * 这里不直接在 reader/writer 中读取 `datasource_config`，而是抽象出连接提供者，
 * 让连接加载、生命周期校验、驱动装载、凭据读取和未来连接池/密钥管理替换集中在一个边界内。</p>
 */
public interface SyncJdbcConnectionProvider {

    /**
     * 打开 JDBC 连接。
     *
     * @param datasourceId 数据源配置 ID。
     * @param readOnly 是否以只读模式打开。reader 应传 true，writer 应传 false。
     * @return 已打开的 JDBC connection，调用方负责关闭。
     */
    Connection openConnection(Long datasourceId, boolean readOnly) throws SQLException, ClassNotFoundException;
}
