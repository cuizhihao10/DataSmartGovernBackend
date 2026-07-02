/**
 * @Author : Cui
 * @Date: 2026/07/02 19:10
 * @Description DataSmartGovernBackend - ObservabilityPostgreSqlMigrationIntegrationTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.observability.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * observability PostgreSQL 真实集成测试。
 *
 * <p>为什么不使用 H2 模拟 PostgreSQL：
 * H2 无法可靠覆盖 PostgreSQL 的 schema search_path、timestamptz、pgvector extension、ON CONFLICT、
 * Flyway PostgreSQL database module 等行为。数据库迁移最容易出现的错误恰好都在这些方言边界，
 * 因此本测试只在显式设置 {@code DATASMART_POSTGRES_INTEGRATION_ENABLED=true} 时连接真实容器。</p>
 *
 * <p>安全与 CI 边界：
 * 测试不创建或删除数据库，不执行 Flyway clean，只读取当前 schema、extension 和 V1 元数据。
 * 默认环境没有启用开关时测试会跳过，普通单元测试不依赖 Docker；本地 E2E、CI integration stage
 * 或发布验收必须显式开启。</p>
 */
@SpringBootTest(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.kafka.listener.auto-startup=false"
})
@EnabledIfEnvironmentVariable(named = "DATASMART_POSTGRES_INTEGRATION_ENABLED", matches = "(?i)true")
class ObservabilityPostgreSqlMigrationIntegrationTest {

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    ObservabilityPostgreSqlMigrationIntegrationTest(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 验证连接、schema、pgvector 和 Flyway 基线。
     *
     * <p>这四项同时通过，才能说明服务真正迁入 PostgreSQL：
     * 仅仅把 JDBC URL 改成 PostgreSQL 并不能证明 search_path 正确，也不能证明迁移脚本已经执行。</p>
     */
    @Test
    void shouldUseObservabilitySchemaAndApplyPostgreSqlBaseline() {
        String currentSchema = jdbcTemplate.queryForObject("SELECT current_schema()", String.class);
        String vectorVersion = jdbcTemplate.queryForObject(
                "SELECT extversion FROM pg_extension WHERE extname = 'vector'",
                String.class
        );
        String databaseEngine = jdbcTemplate.queryForObject(
                "SELECT metadata_value FROM observability_schema_metadata WHERE metadata_key = 'database.engine'",
                String.class
        );
        Integer flywaySuccessCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success = true",
                Integer.class
        );

        assertThat(currentSchema).isEqualTo("observability");
        assertThat(vectorVersion).isEqualTo("0.8.3");
        assertThat(databaseEngine).isEqualTo("postgresql");
        assertThat(flywaySuccessCount).isEqualTo(1);
    }
}
