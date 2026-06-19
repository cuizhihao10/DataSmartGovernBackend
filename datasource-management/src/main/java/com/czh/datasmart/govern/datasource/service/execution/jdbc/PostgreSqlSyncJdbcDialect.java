/**
 * @Author : Cui
 * @Date: 2026/06/20 03:05
 * @Description DataSmart Govern Backend - PostgreSqlSyncJdbcDialect.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.execution.jdbc;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * PostgreSQL JDBC 同步方言。
 *
 * <p>PostgreSQL 与 MySQL 最大的差异在冲突写入：
 * PostgreSQL 使用 `ON CONFLICT (...) DO UPDATE/DO NOTHING`，并通过 `EXCLUDED.column` 引用待写入的新值。
 * 将这部分封装在独立方言中，可以让 worker 不需要关心不同数据库的 upsert 语法差异。</p>
 */
@Component
public class PostgreSqlSyncJdbcDialect extends AbstractSyncJdbcDialect {

    @Override
    public String connectorType() {
        return "POSTGRESQL";
    }

    @Override
    protected SyncPreparedJdbcStatement buildUpsertWriteStatement(SyncJdbcWriteStatementSpec spec) {
        List<String> primaryKeys = requirePrimaryKeys(spec);
        List<String> updateColumns = nonPrimaryKeyColumns(spec);
        if (updateColumns.isEmpty()) {
            throw new IllegalArgumentException("PostgreSQL UPSERT 至少需要一个非主键字段用于冲突更新");
        }
        String conflictColumns = primaryKeys.stream().map(this::quoteIdentifier).reduce((left, right) -> left + ", " + right).orElseThrow();
        String updates = updateColumns.stream()
                .map(column -> quoteIdentifier(column) + " = EXCLUDED." + quoteIdentifier(column))
                .reduce((left, right) -> left + ", " + right)
                .orElseThrow();
        String sql = appendInsertSql(qualifiedObject(spec.getObjectLocator()), quotedColumns(spec.getColumns()), placeholders(spec.getColumns().size()))
                + " ON CONFLICT (" + conflictColumns + ") DO UPDATE SET " + updates;
        return statement("UPSERT_WRITE", sql, List.copyOf(spec.getColumns()));
    }

    @Override
    protected SyncPreparedJdbcStatement buildInsertIgnoreWriteStatement(SyncJdbcWriteStatementSpec spec) {
        List<String> primaryKeys = requirePrimaryKeys(spec);
        String conflictColumns = primaryKeys.stream().map(this::quoteIdentifier).reduce((left, right) -> left + ", " + right).orElseThrow();
        String sql = appendInsertSql(qualifiedObject(spec.getObjectLocator()), quotedColumns(spec.getColumns()), placeholders(spec.getColumns().size()))
                + " ON CONFLICT (" + conflictColumns + ") DO NOTHING";
        return statement("INSERT_IGNORE_WRITE", sql, List.copyOf(spec.getColumns()));
    }

    @Override
    protected SyncPreparedJdbcStatement buildReplaceWriteStatement(SyncJdbcWriteStatementSpec spec) {
        SyncPreparedJdbcStatement statement = buildUpsertWriteStatement(spec);
        statement.setExecutionIntent("REPLACE_WRITE_EMULATED_BY_UPSERT");
        return statement;
    }

    @Override
    protected String quoteOpen() {
        return "\"";
    }

    @Override
    protected String quoteClose() {
        return "\"";
    }
}
