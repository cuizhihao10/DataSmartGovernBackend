/**
 * @Author : Cui
 * @Date: 2026/06/20 03:05
 * @Description DataSmart Govern Backend - SyncJdbcDialectRegistryTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.execution.jdbc;

import com.czh.datasmart.govern.datasource.support.SyncWriteStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JDBC 方言注册表与 SQL 模板生成测试。
 *
 * <p>这组测试不是为了证明数据库已经被真实写入，而是为了验证“执行计划到 worker 内部 SQL 语义”的关键边界：</p>
 * <p>1. 不同连接器类型能够找到对应方言；</p>
 * <p>2. SQL 模板只包含占位符，不包含真实业务值；</p>
 * <p>3. 不同数据库的 upsert/ignore/merge 语法差异被封装在方言层；</p>
 * <p>4. 不安全对象名会被拒绝，避免 worker 把恶意标识符拼接进 SQL；</p>
 * <p>5. 破坏性 OVERWRITE 当前不允许直接生成 SQL，必须等审批、备份和回滚闭环接入后再开放。</p>
 */
class SyncJdbcDialectRegistryTest {

    private final SyncJdbcDialectRegistry registry = new SyncJdbcDialectRegistry(List.of(
            new MySqlSyncJdbcDialect(),
            new PostgreSqlSyncJdbcDialect(),
            new SqlServerSyncJdbcDialect()
    ));

    @Test
    void registryShouldNormalizeCommonConnectorAliases() {
        assertEquals("MYSQL", registry.getRequiredDialect("mysql").connectorType());
        assertEquals("POSTGRESQL", registry.getRequiredDialect("pgsql").connectorType());
        assertEquals("POSTGRESQL", registry.getRequiredDialect("postgres").connectorType());
        assertEquals("SQL_SERVER", registry.getRequiredDialect("sqlserver").connectorType());
        assertEquals("SQL_SERVER", registry.getRequiredDialect("mssql").connectorType());
    }

    @Test
    void mysqlDialectShouldBuildIncrementalReadAndUpsertTemplates() {
        SyncJdbcDialect dialect = registry.getRequiredDialect("MYSQL");

        SyncPreparedJdbcStatement readStatement = dialect.buildIncrementalReadStatement(new SyncJdbcReadStatementSpec(
                "ods.orders",
                List.of("id", "updated_at", "amount"),
                "updated_at",
                "INCREMENTAL_TIME_WINDOW",
                500
        ));

        assertEquals("INCREMENTAL_READ", readStatement.getExecutionIntent());
        assertEquals(List.of("checkpointValue", "limit"), readStatement.getParameterNames());
        assertTrue(readStatement.getSql().contains("`ods`.`orders`"));
        assertTrue(readStatement.getSql().contains("WHERE `updated_at` > ?"));
        assertFalse(readStatement.getSql().contains("2026-01-01"));

        SyncPreparedJdbcStatement writeStatement = dialect.buildConflictAwareWriteStatement(writeSpec(SyncWriteStrategy.UPSERT));
        assertEquals("UPSERT_WRITE", writeStatement.getExecutionIntent());
        assertEquals(List.of("id", "name", "amount"), writeStatement.getParameterNames());
        assertTrue(writeStatement.getSql().contains("ON DUPLICATE KEY UPDATE"));
        assertTrue(writeStatement.getSafetyNotes().contains("DO_NOT_EXPOSE_SQL_IN_API_EVENT_OR_AUDIT_PROJECTION"));
    }

    @Test
    void postgreSqlDialectShouldBuildInsertIgnoreWithConflictClause() {
        SyncJdbcDialect dialect = registry.getRequiredDialect("POSTGRESQL");

        SyncPreparedJdbcStatement statement = dialect.buildConflictAwareWriteStatement(writeSpec(SyncWriteStrategy.INSERT_IGNORE));

        assertEquals("INSERT_IGNORE_WRITE", statement.getExecutionIntent());
        assertTrue(statement.getSql().contains("ON CONFLICT (\"id\") DO NOTHING"));
        assertTrue(statement.getSql().contains("\"dwd\".\"orders\""));
        assertFalse(statement.getSql().contains("secret-value"));
    }

    @Test
    void sqlServerDialectShouldUseTopAndMergeTemplates() {
        SyncJdbcDialect dialect = registry.getRequiredDialect("SQL_SERVER");

        SyncPreparedJdbcStatement readStatement = dialect.buildIncrementalReadStatement(new SyncJdbcReadStatementSpec(
                "ods.orders",
                List.of("id", "updated_at", "amount"),
                "updated_at",
                "INCREMENTAL_ID_RANGE",
                200
        ));

        assertEquals(List.of("limit", "checkpointValue"), readStatement.getParameterNames());
        assertTrue(readStatement.getSql().startsWith("SELECT TOP (?)"));
        assertTrue(readStatement.getSql().contains("[ods].[orders]"));

        SyncPreparedJdbcStatement writeStatement = dialect.buildConflictAwareWriteStatement(writeSpec(SyncWriteStrategy.REPLACE));
        assertEquals("REPLACE_WRITE_EMULATED_BY_MERGE", writeStatement.getExecutionIntent());
        assertTrue(writeStatement.getSql().contains("MERGE INTO [dwd].[orders] AS target"));
        assertTrue(writeStatement.getSql().contains("WHEN MATCHED THEN UPDATE SET"));
        assertTrue(writeStatement.getSql().contains("WHEN NOT MATCHED THEN INSERT"));
    }

    @Test
    void dialectShouldRejectUnsafeObjectOrColumnIdentifier() {
        SyncJdbcDialect dialect = registry.getRequiredDialect("MYSQL");

        assertThrows(IllegalArgumentException.class, () -> dialect.buildFullReadStatement(new SyncJdbcReadStatementSpec(
                "ods.orders where 1=1",
                List.of("id"),
                null,
                "FULL_OBJECT_SCAN",
                100
        )));

        assertThrows(IllegalArgumentException.class, () -> dialect.buildAppendWriteStatement(new SyncJdbcWriteStatementSpec(
                "dwd.orders",
                List.of("id", "name;drop_table"),
                List.of("id"),
                SyncWriteStrategy.APPEND,
                100
        )));
    }

    @Test
    void conflictWriteShouldRequirePrimaryKeyAndRejectOverwrite() {
        SyncJdbcDialect dialect = registry.getRequiredDialect("POSTGRESQL");

        assertThrows(IllegalArgumentException.class, () -> dialect.buildConflictAwareWriteStatement(new SyncJdbcWriteStatementSpec(
                "dwd.orders",
                List.of("id", "name"),
                List.of(),
                SyncWriteStrategy.UPSERT,
                100
        )));

        assertThrows(UnsupportedOperationException.class, () -> dialect.buildConflictAwareWriteStatement(new SyncJdbcWriteStatementSpec(
                "dwd.orders",
                List.of("id", "name"),
                List.of("id"),
                SyncWriteStrategy.OVERWRITE,
                100
        )));
    }

    private SyncJdbcWriteStatementSpec writeSpec(SyncWriteStrategy writeStrategy) {
        return new SyncJdbcWriteStatementSpec(
                "dwd.orders",
                List.of("id", "name", "amount"),
                List.of("id"),
                writeStrategy,
                1000
        );
    }
}
