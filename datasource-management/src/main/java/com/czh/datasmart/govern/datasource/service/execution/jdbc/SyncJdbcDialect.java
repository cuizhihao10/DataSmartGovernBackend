/**
 * @Author : Cui
 * @Date: 2026/06/20 03:05
 * @Description DataSmart Govern Backend - SyncJdbcDialect.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.execution.jdbc;

/**
 * JDBC 方言接口。
 *
 * <p>数据同步进入真实执行阶段后，不能再假设所有数据库都使用同一种 SQL。
 * MySQL 使用反引号和 `ON DUPLICATE KEY UPDATE`，PostgreSQL 使用双引号和 `ON CONFLICT`，
 * SQL Server 则常使用方括号和 `MERGE`。如果把这些差异散落在 worker 代码里，
 * 后续新增 Oracle、ClickHouse 或 Hive 时会迅速失控。</p>
 *
 * <p>该接口把方言差异集中到独立组件中，worker 只需要根据连接器类型拿到 dialect，
 * 再请求 dialect 生成内部 PreparedStatement 模板。</p>
 */
public interface SyncJdbcDialect {

    /**
     * 方言主连接器类型。
     * 示例：MYSQL、POSTGRESQL、SQL_SERVER。
     */
    String connectorType();

    /**
     * 判断当前方言是否支持传入连接器类型。
     * registry 会先做别名归一化，这里主要用于保留扩展点。
     */
    boolean supportsConnector(String connectorType);

    /**
     * 构建全量读取 SQL。
     * 只生成 SQL 模板，不绑定业务参数，也不读取真实数据。
     */
    SyncPreparedJdbcStatement buildFullReadStatement(SyncJdbcReadStatementSpec spec);

    /**
     * 构建增量读取 SQL。
     * checkpoint 真实值必须由 PreparedStatement 参数绑定，不允许拼接进 SQL。
     */
    SyncPreparedJdbcStatement buildIncrementalReadStatement(SyncJdbcReadStatementSpec spec);

    /**
     * 构建自定义 SQL 结果集读取 SQL。
     *
     * <p>该能力只用于 {@code CUSTOM_SQL_QUERY} 离线传输。传入 SQL 必须已经由 data-sync 做过只读门禁，
     * 方言层仍会进行防御性校验，并把查询包装成分页结果集，避免一次性把大结果集全部加载到内存。</p>
     */
    SyncPreparedJdbcStatement buildCustomSqlReadStatement(SyncJdbcReadStatementSpec spec);

    /**
     * 构建追加写入 SQL。
     */
    SyncPreparedJdbcStatement buildAppendWriteStatement(SyncJdbcWriteStatementSpec spec);

    /**
     * 构建冲突感知写入 SQL。
     * UPSERT、INSERT_IGNORE、REPLACE 等策略都属于这一类。
     */
    SyncPreparedJdbcStatement buildConflictAwareWriteStatement(SyncJdbcWriteStatementSpec spec);
}
