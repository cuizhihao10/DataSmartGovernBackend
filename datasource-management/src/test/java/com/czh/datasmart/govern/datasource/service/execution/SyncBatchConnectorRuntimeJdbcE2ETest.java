/**
 * @Author : Cui
 * @Date: 2026/07/06 23:58
 * @Description DataSmart Govern Backend - SyncBatchConnectorRuntimeJdbcE2ETest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.execution;

import com.czh.datasmart.govern.datasource.controller.dto.SyncBatchExecutionPlan;
import com.czh.datasmart.govern.datasource.controller.dto.SyncBatchRunOnceInternalRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncBatchRunOnceInternalResponse;
import com.czh.datasmart.govern.datasource.service.execution.jdbc.JdbcSyncBatchReader;
import com.czh.datasmart.govern.datasource.service.execution.jdbc.JdbcSyncBatchWriter;
import com.czh.datasmart.govern.datasource.service.execution.jdbc.PostgreSqlSyncJdbcDialect;
import com.czh.datasmart.govern.datasource.service.execution.jdbc.SyncJdbcConnectionProvider;
import com.czh.datasmart.govern.datasource.service.execution.jdbc.SyncJdbcDialectRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * datasource-management JDBC run-once 本地端到端测试。
 *
 * <p>为什么要新增这个测试：</p>
 * <p>1. 旧的 reader/writer 测试使用 JDK 动态代理模拟 JDBC，能验证参数绑定和事务调用，但不能证明 SQL 真能执行；</p>
 * <p>2. data-sync 当前控制面已经可以生成 run-once 请求、OBJECT_LIST fan-out 和对象级重试，但最终闭环仍依赖
 * datasource-management 的 Java Reader/Writer 真正把源端数据写入目标端；</p>
 * <p>3. 本测试用 H2 内存库跑真实 JDBC、真实建表、真实 SELECT/INSERT、真实事务提交，作为不依赖外部 Docker
 * 的最小 E2E 守门员。</p>
 *
 * <p>测试边界说明：H2 不是生产连接器，所以这里不声称已经完成 PostgreSQL/MySQL/SQL Server 的真实环境验收。
 * 它只证明“run-once 服务 + 方言 SQL 模板 + JDBC reader + 字段映射 + JDBC writer”的代码路径可以真实执行。
 * 后续预生产验收仍需要用真实 PostgreSQL/MySQL 容器或客户环境再跑一次。</p>
 */
class SyncBatchConnectorRuntimeJdbcE2ETest {

    /**
     * 验证 FULL 离线同步的最小真实闭环。
     *
     * <p>业务场景：用户只希望把源端 EAST 区域客户同步到目标端清洗表，并把字段名从源端命名改成目标端命名。
     * 这个场景覆盖真实产品中最常见的配置项：源端表、目标端表、字段映射、where 过滤、批次大小和完成判断。</p>
     */
    @Test
    void fullRunOnceShouldFilterByComplexWhereMapFieldsPageByOffsetAndWriteTargetRows() throws Exception {
        String jdbcUrl = "jdbc:h2:mem:datasmart_sync_e2e_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1";
        Class.forName("org.h2.Driver");
        initializeSourceAndTargetTables(jdbcUrl);
        SyncBatchConnectorRuntimeRunOnceService service = runOnceService(jdbcUrl);

        /*
         * 第一批：region='EAST' 的源端记录一共 2 条，fetchSize 也是 2。
         * reader 会读到 2 条并写入目标表；因为“读到条数 == fetchSize”，当前批次不能判断源端已经结束，
         * 所以响应应为 BATCH_WRITTEN_MORE_REMAIN，提示 data-sync 继续派发下一批。
         */
        SyncBatchRunOnceInternalResponse firstBatch = service.runOnce(runRequest(0L, 0L, 0L));
        assertThat(firstBatch.getRunStatus()).isEqualTo("BATCH_WRITTEN_MORE_REMAIN");
        assertThat(firstBatch.getBatchRecordsRead()).isEqualTo(2L);
        assertThat(firstBatch.getBatchRecordsWritten()).isEqualTo(2L);
        assertThat(firstBatch.getEndOfSource()).isFalse();
        assertThat(firstBatch.getCompleteCallbackRecommended()).isFalse();

        /*
         * 第二批：data-sync 会把上一批累计读取数作为 previousRecordsRead 传回 run-once。
         * FULL_OBJECT_SCAN 的读语句会把它绑定为 OFFSET，因此第二批会从 EAST 过滤结果的第 3 条开始读。
         * 当前没有更多 EAST 记录，所以 reader 读到 0 条，run-once 建议 complete。
         */
        SyncBatchRunOnceInternalResponse finalBatch = service.runOnce(runRequest(
                firstBatch.getTotalRecordsRead(),
                firstBatch.getTotalRecordsWritten(),
                firstBatch.getTotalFailedRecordCount()));
        assertThat(finalBatch.getRunStatus()).isEqualTo("SOURCE_EXHAUSTED_COMPLETE_REQUIRED");
        assertThat(finalBatch.getBatchRecordsRead()).isZero();
        assertThat(finalBatch.getTotalRecordsRead()).isEqualTo(2L);
        assertThat(finalBatch.getTotalRecordsWritten()).isEqualTo(2L);
        assertThat(finalBatch.getEndOfSource()).isTrue();
        assertThat(finalBatch.getCompleteCallbackRecommended()).isTrue();

        /*
         * 最终断言目标表真实数据，而不是只看返回计数。
         * 这里能证明：where 过滤生效，WEST 记录没有写入；字段映射生效，customer_id/customer_name
         * 已经写成 id/name；稳定排序和 OFFSET 没有造成重复写入。
         */
        assertThat(queryTargetRows(jdbcUrl)).containsExactly(
                Map.of("id", 1001L, "name", "Alice", "amount", new BigDecimal("19.80"), "region", "EAST"),
                Map.of("id", 1003L, "name", "Carol", "amount", new BigDecimal("45.00"), "region", "EAST")
        );
    }

    private SyncBatchConnectorRuntimeRunOnceService runOnceService(String jdbcUrl) {
        SyncJdbcConnectionProvider connectionProvider = new H2SyncJdbcConnectionProvider(jdbcUrl);
        return new SyncBatchConnectorRuntimeRunOnceService(
                new SyncBatchExecutionPreparationService(
                        new SyncJdbcDialectRegistry(List.of(new PostgreSqlSyncJdbcDialect()))),
                new JdbcSyncBatchReader(connectionProvider),
                new JdbcSyncBatchWriter(connectionProvider)
        );
    }

    private SyncBatchRunOnceInternalRequest runRequest(Long previousRecordsRead,
                                                       Long previousRecordsWritten,
                                                       Long previousFailedRecordCount) {
        SyncBatchRunOnceInternalRequest request = new SyncBatchRunOnceInternalRequest();
        request.setExecutionPlan(fullPlan());
        request.setSelectedColumns(List.of("customer_id", "customer_name", "amount", "region"));
        request.setWriteColumns(List.of("id", "name", "amount", "region"));
        request.setPrimaryKeyColumns(List.of("id"));
        request.setActorId(7001L);
        request.setActorRole("SERVICE_ACCOUNT");
        request.setActorTenantId(1L);
        request.setShardOrPartition("single-h2-e2e");
        request.setPreviousRecordsRead(previousRecordsRead);
        request.setPreviousRecordsWritten(previousRecordsWritten);
        request.setPreviousFailedRecordCount(previousFailedRecordCount);
        return request;
    }

    private SyncBatchExecutionPlan fullPlan() {
        return new SyncBatchExecutionPlan(
                "datasmart.datasource.sync-batch-plan.v1",
                "BATCH_EXECUTION_CONTRACT_NO_RAW_SQL_NO_CREDENTIALS",
                100L,
                900L,
                new SyncBatchExecutionPlan.ReadPlan(
                        "POSTGRESQL",
                        10L,
                        "ods.customers",
                        "FULL_OBJECT_SCAN",
                        "FULL",
                        null,
                        List.of(),
                        """
                                ("region" = 'EAST' OR "amount" > 999)
                                AND "customer_id" IN (
                                    SELECT "customer_id"
                                    FROM "ods"."customers"
                                    WHERE LOWER("customer_name") <> 'bob'
                                )
                                """,
                        false,
                        2,
                        List.of("JDBC_BATCH_READ", "SQL_PREDICATE_FILTER_READ", "OFFSET_PAGE_READ")
                ),
                new SyncBatchExecutionPlan.WritePlan(
                        "POSTGRESQL",
                        20L,
                        "dwd.customers_clean",
                        "APPEND",
                        "APPEND_ONLY",
                        false,
                        "id",
                        2,
                        2,
                        List.of("JDBC_BATCH_WRITE", "FIELD_MAPPING_WRITE")
                ),
                new SyncBatchExecutionPlan.CheckpointPlan(
                        "NONE_OR_FINAL_WATERMARK",
                        "FULL_SCAN_OFFSET_FROM_PREVIOUS_RECORDS_READ",
                        false,
                        false,
                        2,
                        "FULL_SYNC_NO_INCREMENTAL_CHECKPOINT_VALUE"
                ),
                new SyncBatchExecutionPlan.RuntimeControlPlan(
                        "datasource-h2-e2e-worker",
                        LocalDateTime.now().plusMinutes(5),
                        true,
                        600,
                        1,
                        "taskId:100/executionId:900/full-h2-e2e",
                        List.of("progress", "complete", "fail")
                ),
                List.of("E2E 使用 H2 内存库验证真实 JDBC 路径，预生产仍需真实 PostgreSQL/MySQL 验收"),
                LocalDateTime.now()
        );
    }

    private void initializeSourceAndTargetTables(String jdbcUrl) throws Exception {
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE SCHEMA "ods"
                    """);
            statement.execute("""
                    CREATE SCHEMA "dwd"
                    """);
            statement.execute("""
                    CREATE TABLE "ods"."customers" (
                        "customer_id" BIGINT PRIMARY KEY,
                        "customer_name" VARCHAR(80) NOT NULL,
                        "amount" DECIMAL(10, 2) NOT NULL,
                        "region" VARCHAR(20) NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE "dwd"."customers_clean" (
                        "id" BIGINT PRIMARY KEY,
                        "name" VARCHAR(80) NOT NULL,
                        "amount" DECIMAL(10, 2) NOT NULL,
                        "region" VARCHAR(20) NOT NULL
                    )
                    """);
            statement.execute("""
                    INSERT INTO "ods"."customers" ("customer_id", "customer_name", "amount", "region") VALUES
                    (1001, 'Alice', 19.80, 'EAST'),
                    (1002, 'Bob', 31.50, 'WEST'),
                    (1003, 'Carol', 45.00, 'EAST')
                    """);
        }
    }

    private List<Map<String, Object>> queryTargetRows(String jdbcUrl) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT "id", "name", "amount", "region"
                     FROM "dwd"."customers_clean"
                     ORDER BY "id"
                     """)) {
            while (resultSet.next()) {
                rows.add(Map.of(
                        "id", resultSet.getLong("id"),
                        "name", resultSet.getString("name"),
                        "amount", resultSet.getBigDecimal("amount"),
                        "region", resultSet.getString("region")
                ));
            }
        }
        return rows;
    }

    /**
     * 测试专用连接提供者。
     *
     * <p>生产环境的 {@code DriverManagerSyncJdbcConnectionProvider} 会根据 datasourceId 查询数据库中的
     * DataSourceConfig，拿到真实 JDBC URL、账号、密码和 driverClassName。E2E 测试不应该依赖业务库和密钥，
     * 因此这里用一个极小实现把 sourceId=10、targetId=20 都映射到同一个 H2 内存库，用不同 schema 模拟
     * 源端和目标端。这样既保留“两个数据源 ID”的执行契约，也避免引入外部环境不稳定因素。</p>
     */
    private static class H2SyncJdbcConnectionProvider implements SyncJdbcConnectionProvider {

        private final String jdbcUrl;

        private H2SyncJdbcConnectionProvider(String jdbcUrl) {
            this.jdbcUrl = jdbcUrl;
        }

        @Override
        public Connection openConnection(Long datasourceId, boolean readOnly) throws SQLException {
            assertThat(datasourceId).isIn(10L, 20L);
            Connection connection = DriverManager.getConnection(jdbcUrl);
            connection.setReadOnly(readOnly);
            return connection;
        }
    }
}
