/**
 * @Author : Cui
 * @Date: 2026/06/20 03:05
 * @Description DataSmart Govern Backend - SqlServerSyncJdbcDialect.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.execution.jdbc;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * SQL Server JDBC 同步方言。
 *
 * <p>SQL Server 在分页/限量读取和冲突写入上与 MySQL/PostgreSQL 不同：
 * 简单首批读取更常使用 `TOP (?)`，冲突写入通常使用 `MERGE`。
 * 本方言先覆盖单批 PreparedStatement 模板，为后续 worker 批处理循环、checkpoint 推进和事务控制打基础。</p>
 */
@Component
public class SqlServerSyncJdbcDialect extends AbstractSyncJdbcDialect {

    @Override
    public String connectorType() {
        return "SQL_SERVER";
    }

    @Override
    protected String buildFullReadSql(String projection, String qualifiedObject, int limit) {
        return "SELECT TOP (?) " + projection + " FROM " + qualifiedObject;
    }

    @Override
    protected String buildIncrementalReadSql(String projection, String qualifiedObject, String checkpointColumn, int limit) {
        return "SELECT TOP (?) " + projection + " FROM " + qualifiedObject
                + " WHERE " + checkpointColumn + " > ? ORDER BY " + checkpointColumn + " ASC";
    }

    @Override
    protected List<String> incrementalReadParameterNames() {
        return List.of("limit", "checkpointValue");
    }

    @Override
    protected SyncPreparedJdbcStatement buildUpsertWriteStatement(SyncJdbcWriteStatementSpec spec) {
        requirePrimaryKeys(spec);
        List<String> updateColumns = nonPrimaryKeyColumns(spec);
        if (updateColumns.isEmpty()) {
            throw new IllegalArgumentException("SQL Server UPSERT 至少需要一个非主键字段用于冲突更新");
        }
        return statement("UPSERT_WRITE", mergeSql(spec, true, true), List.copyOf(spec.getColumns()));
    }

    @Override
    protected SyncPreparedJdbcStatement buildInsertIgnoreWriteStatement(SyncJdbcWriteStatementSpec spec) {
        requirePrimaryKeys(spec);
        return statement("INSERT_IGNORE_WRITE", mergeSql(spec, false, true), List.copyOf(spec.getColumns()));
    }

    @Override
    protected SyncPreparedJdbcStatement buildReplaceWriteStatement(SyncJdbcWriteStatementSpec spec) {
        requirePrimaryKeys(spec);
        return statement("REPLACE_WRITE_EMULATED_BY_MERGE", mergeSql(spec, true, true), List.copyOf(spec.getColumns()));
    }

    /**
     * 构建 SQL Server MERGE 模板。
     *
     * <p>这里使用一行 `VALUES (?, ?...)` 作为 source。
     * 真实 worker 做批量写入时可以按批循环绑定，也可以后续扩展为表值参数或临时表方案。
     * 当前先用最容易验证和理解的形式把幂等语义打通。</p>
     */
    private String mergeSql(SyncJdbcWriteStatementSpec spec, boolean updateWhenMatched, boolean insertWhenNotMatched) {
        String target = qualifiedObject(spec.getObjectLocator());
        List<String> quotedColumns = quotedColumns(spec.getColumns());
        String sourceColumns = quotedColumns.stream()
                .map(column -> column)
                .reduce((left, right) -> left + ", " + right)
                .orElseThrow();
        String onClause = requirePrimaryKeys(spec).stream()
                .map(column -> "target." + quoteIdentifier(column) + " = source." + quoteIdentifier(column))
                .reduce((left, right) -> left + " AND " + right)
                .orElseThrow();
        StringBuilder sql = new StringBuilder();
        sql.append("MERGE INTO ").append(target).append(" AS target USING (VALUES (")
                .append(placeholders(spec.getColumns().size())).append(")) AS source (")
                .append(sourceColumns).append(") ON ").append(onClause).append(" ");
        if (updateWhenMatched) {
            String updates = nonPrimaryKeyColumns(spec).stream()
                    .map(column -> "target." + quoteIdentifier(column) + " = source." + quoteIdentifier(column))
                    .reduce((left, right) -> left + ", " + right)
                    .orElseThrow();
            sql.append("WHEN MATCHED THEN UPDATE SET ").append(updates).append(" ");
        }
        if (insertWhenNotMatched) {
            String insertValues = spec.getColumns().stream()
                    .map(column -> "source." + quoteIdentifier(column))
                    .reduce((left, right) -> left + ", " + right)
                    .orElseThrow();
            sql.append("WHEN NOT MATCHED THEN INSERT (").append(String.join(", ", quotedColumns))
                    .append(") VALUES (").append(insertValues).append(") ");
        }
        return sql.append(";").toString();
    }

    @Override
    protected String quoteOpen() {
        return "[";
    }

    @Override
    protected String quoteClose() {
        return "]";
    }
}
