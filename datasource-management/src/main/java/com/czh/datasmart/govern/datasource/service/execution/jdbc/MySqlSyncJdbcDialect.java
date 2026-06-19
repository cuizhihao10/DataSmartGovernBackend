/**
 * @Author : Cui
 * @Date: 2026/06/20 03:05
 * @Description DataSmart Govern Backend - MySqlSyncJdbcDialect.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.execution.jdbc;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MySQL JDBC 同步方言。
 *
 * <p>MySQL 是当前项目最先落地的数据同步连接器之一，因此这里优先覆盖三类常用写入语义：</p>
 * <p>1. APPEND：普通 `INSERT INTO`，由基类实现；</p>
 * <p>2. UPSERT：使用 `ON DUPLICATE KEY UPDATE`，要求目标端存在主键或唯一键；</p>
 * <p>3. INSERT_IGNORE/REPLACE：分别使用 MySQL 原生 `INSERT IGNORE` 和 `REPLACE INTO`。</p>
 *
 * <p>注意：这里生成的是内部 SQL 模板，不携带业务值。
 * 后续 worker 必须使用 PreparedStatement 按 `parameterNames` 绑定字段值，不能拼接行数据。</p>
 */
@Component
public class MySqlSyncJdbcDialect extends AbstractSyncJdbcDialect {

    @Override
    public String connectorType() {
        return "MYSQL";
    }

    @Override
    protected SyncPreparedJdbcStatement buildUpsertWriteStatement(SyncJdbcWriteStatementSpec spec) {
        requirePrimaryKeys(spec);
        List<String> updateColumns = nonPrimaryKeyColumns(spec);
        if (updateColumns.isEmpty()) {
            throw new IllegalArgumentException("MySQL UPSERT 至少需要一个非主键字段用于冲突更新");
        }
        String updates = updateColumns.stream()
                .map(column -> quoteIdentifier(column) + " = VALUES(" + quoteIdentifier(column) + ")")
                .reduce((left, right) -> left + ", " + right)
                .orElseThrow();
        String sql = appendInsertSql(qualifiedObject(spec.getObjectLocator()), quotedColumns(spec.getColumns()), placeholders(spec.getColumns().size()))
                + " ON DUPLICATE KEY UPDATE " + updates;
        return statement("UPSERT_WRITE", sql, List.copyOf(spec.getColumns()));
    }

    @Override
    protected SyncPreparedJdbcStatement buildInsertIgnoreWriteStatement(SyncJdbcWriteStatementSpec spec) {
        requirePrimaryKeys(spec);
        String sql = "INSERT IGNORE INTO " + qualifiedObject(spec.getObjectLocator())
                + " (" + String.join(", ", quotedColumns(spec.getColumns())) + ") VALUES (" + placeholders(spec.getColumns().size()) + ")";
        return statement("INSERT_IGNORE_WRITE", sql, List.copyOf(spec.getColumns()));
    }

    @Override
    protected SyncPreparedJdbcStatement buildReplaceWriteStatement(SyncJdbcWriteStatementSpec spec) {
        requirePrimaryKeys(spec);
        String sql = "REPLACE INTO " + qualifiedObject(spec.getObjectLocator())
                + " (" + String.join(", ", quotedColumns(spec.getColumns())) + ") VALUES (" + placeholders(spec.getColumns().size()) + ")";
        return statement("REPLACE_WRITE", sql, List.copyOf(spec.getColumns()));
    }

    @Override
    protected String quoteOpen() {
        return "`";
    }

    @Override
    protected String quoteClose() {
        return "`";
    }
}
