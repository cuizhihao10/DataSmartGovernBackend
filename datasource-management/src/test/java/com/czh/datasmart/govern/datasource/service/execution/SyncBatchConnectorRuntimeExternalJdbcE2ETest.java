/**
 * @Author : Cui
 * @Date: 2026/07/07 00:32
 * @Description DataSmart Govern Backend - SyncBatchConnectorRuntimeExternalJdbcE2ETest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.execution;

import com.czh.datasmart.govern.datasource.controller.dto.SyncBatchExecutionPlan;
import com.czh.datasmart.govern.datasource.controller.dto.SyncBatchRunOnceInternalRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncBatchRunOnceInternalResponse;
import com.czh.datasmart.govern.datasource.service.execution.jdbc.JdbcSyncBatchReader;
import com.czh.datasmart.govern.datasource.service.execution.jdbc.JdbcSyncBatchWriter;
import com.czh.datasmart.govern.datasource.service.execution.jdbc.MySqlSyncJdbcDialect;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * datasource-management 外部 JDBC 真实数据库 E2E。
 *
 * <p>本测试与 {@link SyncBatchConnectorRuntimeJdbcE2ETest} 的定位不同：</p>
 * <p>1. H2 E2E 是默认守门测试，用内存库证明 run-once 执行栈能真实执行 SQL，适合每次本地/CI 快速运行；</p>
 * <p>2. 本测试默认跳过，只有显式设置 {@code DATASMART_E2E_REAL_JDBC=true} 时才会连接真实 MySQL/PostgreSQL；</p>
 * <p>3. 它用于验证“源端 MySQL -> 目标端 PostgreSQL”的异构方言链路，包括对象定位、字段映射、where 过滤、
 * OFFSET 多批次推进、目标端 UPSERT 和真实事务提交。</p>
 *
 * <p>安全边界非常重要：测试只会创建/覆盖专用 E2E 表 {@code datasmart_e2e_source_customers}
 * 与 {@code datasmart_e2e.customers_clean}，不会读取用户业务表，不会输出 JDBC URL、账号、密码、SQL 正文或样本行。
 * 如果客户环境需要跑类似验收，应为 E2E 准备独立数据库、独立账号和独立 schema，而不是直接指向生产库。</p>
 */
class SyncBatchConnectorRuntimeExternalJdbcE2ETest {

    private static final Long SOURCE_DATASOURCE_ID = 10L;
    private static final Long TARGET_DATASOURCE_ID = 20L;
    private static final String SOURCE_TABLE = "datasmart_e2e_source_customers";
    private static final String TARGET_SCHEMA = "datasmart_e2e";
    private static final String TARGET_TABLE = "customers_clean";

    /**
     * 验证真实 MySQL 源表到 PostgreSQL 目标表的最小可商用同步闭环。
     *
     * <p>业务场景：用户在数据同步任务中选择一个源端客户表，配置目标端清洗表，设置字段改名
     * {@code customer_id -> id}、{@code customer_name -> name}，并使用 {@code region = EAST} 过滤条件。
     * 控制面会把这些配置变成低敏 {@link SyncBatchExecutionPlan}，执行面在本测试中负责真正读取 MySQL、
     * 写入 PostgreSQL，并返回给 data-sync 可以消费的低敏 run-once 摘要。</p>
     *
     * <p>为什么选择 UPSERT：真实生产同步常常会因为网络抖动、worker 重启、对象级重试而出现重复执行同一批的情况。
     * APPEND 虽然简单，但一旦目标端已经部分提交，再重试就可能产生重复数据。UPSERT 依赖主键或唯一键，把“重复运行”
     * 收敛成“同一业务键更新”，更适合作为默认推荐的商用幂等路径。</p>
     */
    @Test
    void mysqlToPostgresqlRunOnceShouldFilterMapFieldsAndUpsertTargetRows() throws Exception {
        assumeTrue(realJdbcE2EEnabled(), "外部 JDBC E2E 默认跳过；如需真实验收请设置 DATASMART_E2E_REAL_JDBC=true");
        ExternalJdbcSettings settings = ExternalJdbcSettings.fromEnvironment();
        verifyExternalConnections(settings);
        initializeSourceTable(settings);
        initializeTargetTable(settings);
        SyncBatchConnectorRuntimeRunOnceService service = runOnceService(settings);

        /*
         * 第一批：MySQL 源端中 region='EAST' 的记录共 2 条，fetchSize 也为 2。
         * run-once 只能确认“本批写入成功且可能还有下一批”，所以返回 BATCH_WRITTEN_MORE_REMAIN。
         * 这个语义会让 data-sync 继续以 previousRecordsRead=2 派发下一次 run-once。
         */
        SyncBatchRunOnceInternalResponse firstBatch = service.runOnce(runRequest(0L, 0L, 0L));
        assertThat(firstBatch.getRunStatus()).isEqualTo("BATCH_WRITTEN_MORE_REMAIN");
        assertThat(firstBatch.getBatchRecordsRead()).isEqualTo(2L);
        assertThat(firstBatch.getBatchRecordsWritten()).isEqualTo(2L);
        assertThat(firstBatch.getEndOfSource()).isFalse();
        assertThat(firstBatch.getCompleteCallbackRecommended()).isFalse();

        /*
         * 第二批：使用第一批累计读取数作为 OFFSET，继续读取过滤后的结果集。
         * 源端没有更多 EAST 记录，因此本批读取 0 条并建议上游 complete。
         * 注意这里不是测试线程“猜测完成”，而是由 reader 真实读库后的 endOfSource 事实驱动完成判断。
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
         * 最终断言直接查询 PostgreSQL 目标表，而不是只相信 run-once 响应计数。
         * 这能证明：MySQL 方言生成的读取 SQL、PostgreSQL 方言生成的 UPSERT SQL、字段改名、
         * where 过滤、跨库 JDBC 连接和事务提交全部真实生效。
         */
        assertThat(queryTargetRows(settings)).containsExactly(
                Map.of("id", 1001L, "name", "Alice", "amount", new BigDecimal("19.80"), "region", "EAST"),
                Map.of("id", 1003L, "name", "Carol", "amount", new BigDecimal("45.00"), "region", "EAST")
        );
    }

    private SyncBatchConnectorRuntimeRunOnceService runOnceService(ExternalJdbcSettings settings) {
        SyncJdbcConnectionProvider connectionProvider = new ExternalSyncJdbcConnectionProvider(settings);
        return new SyncBatchConnectorRuntimeRunOnceService(
                new SyncBatchExecutionPreparationService(
                        new SyncJdbcDialectRegistry(List.of(new MySqlSyncJdbcDialect(), new PostgreSqlSyncJdbcDialect()))),
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
        request.setShardOrPartition("mysql-to-postgresql-real-e2e");
        request.setPreviousRecordsRead(previousRecordsRead);
        request.setPreviousRecordsWritten(previousRecordsWritten);
        request.setPreviousFailedRecordCount(previousFailedRecordCount);
        return request;
    }

    private SyncBatchExecutionPlan fullPlan() {
        return new SyncBatchExecutionPlan(
                "datasmart.datasource.sync-batch-plan.v1",
                "EXTERNAL_JDBC_E2E_NO_RAW_SQL_NO_CREDENTIALS",
                100L,
                900L,
                new SyncBatchExecutionPlan.ReadPlan(
                        "MYSQL",
                        SOURCE_DATASOURCE_ID,
                        SOURCE_TABLE,
                        "FULL_OBJECT_SCAN",
                        "FULL",
                        null,
                        List.of(new SyncBatchExecutionPlan.ReadFilterCondition("region", "EQ", "EAST", true)),
                        false,
                        2,
                        List.of("JDBC_BATCH_READ", "FILTER_AWARE_READ", "OFFSET_PAGE_READ")
                ),
                new SyncBatchExecutionPlan.WritePlan(
                        "POSTGRESQL",
                        TARGET_DATASOURCE_ID,
                        TARGET_SCHEMA + "." + TARGET_TABLE,
                        "UPSERT",
                        "UPDATE_ON_CONFLICT",
                        true,
                        "id",
                        2,
                        2,
                        List.of("JDBC_BATCH_WRITE", "FIELD_MAPPING_WRITE", "IDEMPOTENT_CONFLICT_WRITE")
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
                        "datasource-external-jdbc-e2e-worker",
                        LocalDateTime.now().plusMinutes(5),
                        true,
                        600,
                        1,
                        "taskId:100/executionId:900/mysql-to-postgresql-real-e2e",
                        List.of("progress", "complete", "fail")
                ),
                List.of("外部 JDBC E2E 只在显式开启时运行，用于验证真实 MySQL 到 PostgreSQL 的异构同步链路"),
                LocalDateTime.now()
        );
    }

    private boolean realJdbcE2EEnabled() {
        String value = System.getenv("DATASMART_E2E_REAL_JDBC");
        return value != null && List.of("true", "1", "yes", "y", "on").contains(value.trim().toLowerCase());
    }

    private void verifyExternalConnections(ExternalJdbcSettings settings) throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");
        Class.forName("org.postgresql.Driver");
        try (Connection ignored = DriverManager.getConnection(settings.mysqlUrl(), settings.mysqlUser(), settings.mysqlPassword())) {
            // 这里只验证连接可打开，不执行任何业务查询，避免把“连通性检查”变成隐式数据访问。
        } catch (SQLException exception) {
            fail("外部 JDBC E2E 已开启，但 MySQL 源端连接不可用；请检查容器、端口、账号或密码，issue="
                    + exception.getClass().getSimpleName());
        }
        try (Connection ignored = DriverManager.getConnection(settings.postgresUrl(), settings.postgresUser(), settings.postgresPassword())) {
            // PostgreSQL 同样只验证连接能力，后续建表动作限制在 datasmart_e2e 专用 schema 内。
        } catch (SQLException exception) {
            fail("外部 JDBC E2E 已开启，但 PostgreSQL 目标端连接不可用；请检查容器、端口、账号或密码，issue="
                    + exception.getClass().getSimpleName());
        }
    }

    private void initializeSourceTable(ExternalJdbcSettings settings) throws Exception {
        try (Connection connection = DriverManager.getConnection(settings.mysqlUrl(), settings.mysqlUser(), settings.mysqlPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS `" + SOURCE_TABLE + "`");
            statement.execute("""
                    CREATE TABLE `datasmart_e2e_source_customers` (
                        `customer_id` BIGINT PRIMARY KEY,
                        `customer_name` VARCHAR(80) NOT NULL,
                        `amount` DECIMAL(10, 2) NOT NULL,
                        `region` VARCHAR(20) NOT NULL
                    )
                    """);
            statement.execute("""
                    INSERT INTO `datasmart_e2e_source_customers` (`customer_id`, `customer_name`, `amount`, `region`) VALUES
                    (1001, 'Alice', 19.80, 'EAST'),
                    (1002, 'Bob', 31.50, 'WEST'),
                    (1003, 'Carol', 45.00, 'EAST')
                    """);
        }
    }

    private void initializeTargetTable(ExternalJdbcSettings settings) throws Exception {
        try (Connection connection = DriverManager.getConnection(settings.postgresUrl(), settings.postgresUser(), settings.postgresPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS \"" + TARGET_SCHEMA + "\"");
            statement.execute("DROP TABLE IF EXISTS \"" + TARGET_SCHEMA + "\".\"" + TARGET_TABLE + "\"");
            statement.execute("""
                    CREATE TABLE "datasmart_e2e"."customers_clean" (
                        "id" BIGINT PRIMARY KEY,
                        "name" VARCHAR(80) NOT NULL,
                        "amount" NUMERIC(10, 2) NOT NULL,
                        "region" VARCHAR(20) NOT NULL
                    )
                    """);
        }
    }

    private List<Map<String, Object>> queryTargetRows(ExternalJdbcSettings settings) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(settings.postgresUrl(), settings.postgresUser(), settings.postgresPassword());
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT "id", "name", "amount", "region"
                     FROM "datasmart_e2e"."customers_clean"
                     ORDER BY "id"
                     """)) {
            while (resultSet.next()) {
                rows.add(Map.of(
                        "id", resultSet.getLong("id"),
                        "name", resultSet.getString("name"),
                        "amount", resultSet.getBigDecimal("amount").setScale(2),
                        "region", resultSet.getString("region")
                ));
            }
        }
        return rows;
    }

    /**
     * 测试期连接参数。
     *
     * <p>真实系统中 datasource-management 会通过 {@code DataSourceConfig}、密钥引用、连接池和权限校验打开连接；
     * 本测试不走业务库读取数据源配置，是为了让“外部数据库 E2E”可以独立验证执行面能力，并避免在测试输出中暴露连接配置。
     * 参数通过环境变量传入，脚本只注入到当前 Maven 进程，测试代码也不打印原始值。</p>
     */
    private record ExternalJdbcSettings(String mysqlUrl,
                                        String mysqlUser,
                                        String mysqlPassword,
                                        String postgresUrl,
                                        String postgresUser,
                                        String postgresPassword) {

        private static ExternalJdbcSettings fromEnvironment() {
            String mysqlPort = envOrDefault("DATASMART_LOCAL_MYSQL_PORT", "13306");
            String postgresPort = envOrDefault("DATASMART_LOCAL_POSTGRES_PORT", "5432");
            String defaultMysqlUrl = "jdbc:mysql://127.0.0.1:" + mysqlPort
                    + "/datasmart_govern?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
                    + "&useSSL=false&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true";
            String defaultPostgresUrl = "jdbc:postgresql://127.0.0.1:" + postgresPort + "/datasmart_govern";
            String mysqlPassword = envOrDefault("DATASMART_E2E_MYSQL_PASSWORD", envOrDefault("DATASMART_MYSQL_PASSWORD", "password"));
            String postgresPassword = envOrDefault("DATASMART_E2E_POSTGRES_PASSWORD", envOrDefault("DATASMART_POSTGRES_PASSWORD", "password"));
            return new ExternalJdbcSettings(
                    envOrDefault("DATASMART_E2E_MYSQL_URL", defaultMysqlUrl),
                    envOrDefault("DATASMART_E2E_MYSQL_USER", envOrDefault("DATASMART_MYSQL_USER", "datasmart")),
                    mysqlPassword,
                    envOrDefault("DATASMART_E2E_POSTGRES_URL", defaultPostgresUrl),
                    envOrDefault("DATASMART_E2E_POSTGRES_USER", envOrDefault("DATASMART_POSTGRES_USER", "datasmart")),
                    postgresPassword
            );
        }

        private static String envOrDefault(String name, String defaultValue) {
            String value = System.getenv(name);
            return value == null || value.isBlank() ? defaultValue : value.trim();
        }
    }

    /**
     * 外部 E2E 专用连接提供者。
     *
     * <p>它只识别两个受控 datasourceId：{@code 10} 表示 MySQL 源端，{@code 20} 表示 PostgreSQL 目标端。
     * 这样测试仍然遵守“源端、目标端是两个数据源 ID”的执行契约，同时不需要依赖业务库里的数据源配置种子数据。</p>
     */
    private static class ExternalSyncJdbcConnectionProvider implements SyncJdbcConnectionProvider {

        private final ExternalJdbcSettings settings;

        private ExternalSyncJdbcConnectionProvider(ExternalJdbcSettings settings) {
            this.settings = settings;
        }

        @Override
        public Connection openConnection(Long datasourceId, boolean readOnly) throws SQLException, ClassNotFoundException {
            if (SOURCE_DATASOURCE_ID.equals(datasourceId)) {
                Class.forName("com.mysql.cj.jdbc.Driver");
                return DriverManager.getConnection(settings.mysqlUrl(), settings.mysqlUser(), settings.mysqlPassword());
            }
            if (TARGET_DATASOURCE_ID.equals(datasourceId)) {
                Class.forName("org.postgresql.Driver");
                return DriverManager.getConnection(settings.postgresUrl(), settings.postgresUser(), settings.postgresPassword());
            }
            throw new IllegalArgumentException("外部 JDBC E2E 只允许访问测试期 source/target datasourceId");
        }
    }
}
