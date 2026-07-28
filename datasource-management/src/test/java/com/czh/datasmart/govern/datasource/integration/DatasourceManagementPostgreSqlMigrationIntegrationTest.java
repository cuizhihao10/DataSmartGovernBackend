package com.czh.datasmart.govern.datasource.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.czh.datasmart.govern.datasource.entity.DataSourceConfig;
import com.czh.datasmart.govern.datasource.entity.DataSourceReadOnlySqlExecutionAudit;
import com.czh.datasmart.govern.datasource.mapper.DataSourceConfigMapper;
import com.czh.datasmart.govern.datasource.mapper.DataSourceReadOnlySqlExecutionAuditMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * datasource-management PostgreSQL 迁移集成测试。
 *
 * <p>同步任务控制面由 data-sync 独占。本测试只验证数据源登记、只读 SQL 审计，以及 V14 已清理
 * datasource-management 中历史重复的模板、任务、执行和回执表。</p>
 */
@SpringBootTest(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.kafka.listener.auto-startup=false"
})
@EnabledIfEnvironmentVariable(named = "DATASMART_POSTGRES_INTEGRATION_ENABLED", matches = "(?i)true")
class DatasourceManagementPostgreSqlMigrationIntegrationTest {

    private final JdbcTemplate jdbcTemplate;
    private final DataSourceConfigMapper datasourceMapper;
    private final DataSourceReadOnlySqlExecutionAuditMapper auditMapper;

    @Autowired
    DatasourceManagementPostgreSqlMigrationIntegrationTest(
            JdbcTemplate jdbcTemplate,
            DataSourceConfigMapper datasourceMapper,
            DataSourceReadOnlySqlExecutionAuditMapper auditMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.datasourceMapper = datasourceMapper;
        this.auditMapper = auditMapper;
    }

    @Test
    void shouldPersistDatasourceFactsAndRemoveLegacySyncControlPlane() {
        assertSchemaBaseline();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        DataSourceConfig datasource = null;
        DataSourceReadOnlySqlExecutionAudit audit = null;
        try {
            datasource = insertDatasource(suffix);
            audit = insertAudit(datasource, suffix);

            Page<DataSourceConfig> page = datasourceMapper.selectPage(
                    new Page<>(1, 10),
                    new LambdaQueryWrapper<DataSourceConfig>()
                            .eq(DataSourceConfig::getTenantId, 910001L)
                            .eq(DataSourceConfig::getProjectId, 910101L)
                            .likeRight(DataSourceConfig::getName, "pg-datasource-")
                            .orderByDesc(DataSourceConfig::getId));

            assertThat(datasource.getId()).isPositive();
            assertThat(audit.getId()).isPositive();
            assertThat(page.getRecords()).extracting(DataSourceConfig::getId).contains(datasource.getId());
        } finally {
            if (audit != null && audit.getId() != null) {
                auditMapper.deleteById(audit.getId());
            }
            if (datasource != null && datasource.getId() != null) {
                datasourceMapper.deleteById(datasource.getId());
            }
        }
    }

    private void assertSchemaBaseline() {
        assertThat(jdbcTemplate.queryForObject("SELECT current_schema()", String.class))
                .isEqualTo("datasource_management");
        Integer removedTableCount = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM information_schema.tables
                WHERE table_schema = 'datasource_management'
                  AND table_name IN (
                      'sync_template',
                      'sync_task',
                      'sync_execution',
                      'sync_checkpoint',
                      'sync_agent_command_receipt'
                  )
                """, Integer.class);
        Integer retainedTableCount = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM information_schema.tables
                WHERE table_schema = 'datasource_management'
                  AND table_name IN (
                      'datasource_config',
                      'datasource_readonly_sql_execution_audit'
                  )
                """, Integer.class);
        assertThat(removedTableCount).isZero();
        assertThat(retainedTableCount).isEqualTo(2);
    }

    private DataSourceConfig insertDatasource(String suffix) {
        DataSourceConfig config = new DataSourceConfig();
        config.setTenantId(910001L);
        config.setProjectId(910101L);
        config.setName("pg-datasource-" + suffix);
        config.setUsagePurpose("SOURCE");
        config.setType("POSTGRESQL");
        config.setJdbcUrl("jdbc:postgresql://example.invalid:5432/integration");
        config.setUsername("integration_user");
        config.setPassword("ENC[v1]:integration-key:test-ciphertext");
        config.setDriverClassName("org.postgresql.Driver");
        config.setDescription("PostgreSQL migration integration datasource");
        config.setStatus("ACTIVE");
        config.setLastTestStatus("SUCCESS");
        config.setLastTestMessage("低敏连接测试摘要");
        config.setLastTestTime(LocalDateTime.now());
        datasourceMapper.insert(config);
        return config;
    }

    private DataSourceReadOnlySqlExecutionAudit insertAudit(DataSourceConfig datasource, String suffix) {
        DataSourceReadOnlySqlExecutionAudit audit = new DataSourceReadOnlySqlExecutionAudit();
        audit.setDatasourceTenantId(datasource.getTenantId());
        audit.setDatasourceProjectId(datasource.getProjectId());
        audit.setDatasourceId(datasource.getId());
        audit.setDatasourceName(datasource.getName());
        audit.setDatasourceType(datasource.getType());
        audit.setPurpose("INTEGRATION_TEST");
        audit.setActorTenantId(datasource.getTenantId());
        audit.setActorId(910301L);
        audit.setActorRole("SERVICE_ACCOUNT");
        audit.setActorType("SERVICE_ACCOUNT");
        audit.setSourceService("datasource-management-integration-test");
        audit.setTraceId("pg-datasource-audit-" + suffix);
        audit.setSqlFingerprint("abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789");
        audit.setSqlPreview("SELECT id, name FROM customer LIMIT 10");
        audit.setRequestedMaxRows(10);
        audit.setAppliedMaxRows(10);
        audit.setRequestedQueryTimeoutSeconds(5);
        audit.setAppliedQueryTimeoutSeconds(5);
        audit.setReturnedRowCount(1);
        audit.setColumnCount(2);
        audit.setDurationMs(15L);
        audit.setExecutionStatus("SUCCESS");
        audit.setExecutedAt(LocalDateTime.now());
        auditMapper.insert(audit);
        return audit;
    }
}
