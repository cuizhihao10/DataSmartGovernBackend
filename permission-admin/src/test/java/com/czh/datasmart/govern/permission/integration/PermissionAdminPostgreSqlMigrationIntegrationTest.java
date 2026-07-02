/**
 * @Author : Cui
 * @Date: 2026/07/02 23:17
 * @Description DataSmartGovernBackend - PermissionAdminPostgreSqlMigrationIntegrationTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.czh.datasmart.govern.permission.entity.PermissionAuditRecord;
import com.czh.datasmart.govern.permission.entity.PermissionEventOutbox;
import com.czh.datasmart.govern.permission.entity.PermissionRole;
import com.czh.datasmart.govern.permission.mapper.PermissionAuditRecordMapper;
import com.czh.datasmart.govern.permission.mapper.PermissionEventOutboxMapper;
import com.czh.datasmart.govern.permission.mapper.PermissionRoleMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * permission-admin PostgreSQL 真实集成测试。
 *
 * <p>本测试验证的是 H2 和 Mock 无法替代的真实数据库边界：
 * PostgreSQL schema search_path、Flyway V1 登记、BOOLEAN 映射、identity 主键回填、
 * MyBatis-Plus PostgreSQL 分页、outbox 时间间隔表达式以及状态条件更新。</p>
 *
 * <p>运行安全边界：
 * 只有显式设置 {@code DATASMART_POSTGRES_INTEGRATION_ENABLED=true} 才会执行。
 * 测试不创建或删除数据库，不执行 Flyway clean，不读取真实敏感业务数据。
 * 本测试创建的审计和 outbox 记录都使用随机 traceId/eventId，并在 finally 中按主键定向删除，
 * 避免污染共享开发数据库。</p>
 */
@SpringBootTest(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "datasmart.permission.policy-events.enabled=false",
        "datasmart.permission.policy-events.dispatcher-enabled=false"
})
@EnabledIfEnvironmentVariable(named = "DATASMART_POSTGRES_INTEGRATION_ENABLED", matches = "(?i)true")
class PermissionAdminPostgreSqlMigrationIntegrationTest {

    private final JdbcTemplate jdbcTemplate;
    private final PermissionRoleMapper roleMapper;
    private final PermissionAuditRecordMapper auditRecordMapper;
    private final PermissionEventOutboxMapper eventOutboxMapper;

    @Autowired
    PermissionAdminPostgreSqlMigrationIntegrationTest(
            JdbcTemplate jdbcTemplate,
            PermissionRoleMapper roleMapper,
            PermissionAuditRecordMapper auditRecordMapper,
            PermissionEventOutboxMapper eventOutboxMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.roleMapper = roleMapper;
        this.auditRecordMapper = auditRecordMapper;
        this.eventOutboxMapper = eventOutboxMapper;
    }

    /**
     * 验证 permission_admin schema 基线、默认角色种子、分页插件和 outbox 状态机。
     *
     * <p>权限中心是 gateway、task-management、data-sync、data-quality 和 Agent Runtime 的共同控制面，
     * 因此迁移不能只证明“表建好了”。本测试同时覆盖：
     * 1. Flyway 是否在正确 schema 记录成功版本；
     * 2. 8 张模块自有表是否存在；
     * 3. 默认角色是否可通过 MyBatis 读取；
     * 4. Page 查询是否经过 PostgreSQL 分页方言；
     * 5. outbox 的失败重试和超时恢复是否仍按生产状态流转。</p>
     */
    @Test
    void shouldApplyPermissionAdminSchemaAndRunCorePostgreSqlPaths() {
        assertPostgreSqlSchemaBaseline();
        assertSeedRolesAndPagination();

        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        PermissionAuditRecord auditRecord = null;
        PermissionEventOutbox outbox = null;
        try {
            auditRecord = insertAuditRecord(suffix);
            outbox = insertOutboxEvent(suffix);
            assertOutboxStateMachine(outbox);
        } finally {
            deleteIntegrationFacts(auditRecord, outbox);
        }
    }

    /**
     * 校验连接确实进入 permission_admin schema，且 Flyway V1 已登记成功。
     */
    private void assertPostgreSqlSchemaBaseline() {
        String currentSchema = jdbcTemplate.queryForObject("SELECT current_schema()", String.class);
        Integer flywaySuccessCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success = true",
                Integer.class
        );
        Integer tableCount = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM information_schema.tables
                WHERE table_schema = 'permission_admin'
                  AND table_name IN (
                      'permission_role',
                      'permission_menu',
                      'permission_role_menu_binding',
                      'permission_route_policy',
                      'permission_data_scope_policy',
                      'permission_project_membership',
                      'permission_audit_record',
                      'permission_event_outbox'
                  )
                """, Integer.class);

        assertThat(currentSchema).isEqualTo("permission_admin");
        assertThat(flywaySuccessCount).isEqualTo(1);
        assertThat(tableCount).isEqualTo(8);
    }

    /**
     * 校验默认角色和 MyBatis-Plus PostgreSQL 分页。
     *
     * <p>如果分页拦截器未生效，这里仍可能查到角色，但 Page 语义和 SQL 物理分页就不可靠。
     * 因此测试不只按 role_code 精确查一条，还走一次 selectPage，覆盖实际管理后台列表路径。</p>
     */
    private void assertSeedRolesAndPagination() {
        PermissionRole platformAdmin = roleMapper.selectOne(
                new LambdaQueryWrapper<PermissionRole>()
                        .eq(PermissionRole::getTenantId, 0L)
                        .eq(PermissionRole::getRoleCode, "PLATFORM_ADMINISTRATOR")
        );
        assertThat(platformAdmin).isNotNull();
        assertThat(platformAdmin.getEnabled()).isTrue();

        Page<PermissionRole> page = roleMapper.selectPage(
                new Page<>(1, 3),
                new LambdaQueryWrapper<PermissionRole>()
                        .eq(PermissionRole::getTenantId, 0L)
                        .orderByAsc(PermissionRole::getRoleCode)
        );
        assertThat(page.getTotal()).isGreaterThanOrEqualTo(7);
        assertThat(page.getRecords()).hasSizeLessThanOrEqualTo(3);
        assertThat(page.getRecords()).extracting(PermissionRole::getRoleCode)
                .containsAnyOf("AUDITOR", "OPERATOR", "ORDINARY_USER", "PLATFORM_ADMINISTRATOR");
    }

    /**
     * 插入一条权限审计记录，验证 identity 主键、TEXT JSON 字段和 LocalDateTime 映射。
     */
    private PermissionAuditRecord insertAuditRecord(String suffix) {
        PermissionAuditRecord auditRecord = new PermissionAuditRecord();
        auditRecord.setTraceId("pg-permission-audit-" + suffix);
        auditRecord.setTenantId(900001L);
        auditRecord.setActorId(900101L);
        auditRecord.setActorRole("OPERATOR");
        auditRecord.setResourceType("PERMISSION_MIGRATION");
        auditRecord.setResourceId("postgresql-baseline");
        auditRecord.setAction("VERIFY_POSTGRESQL_SCHEMA");
        auditRecord.setResult("SUCCESS");
        auditRecord.setSummary("permission-admin PostgreSQL 迁移集成测试审计记录");
        auditRecord.setDetailJson("{\"source\":\"integration-test\",\"database\":\"postgresql\"}");
        auditRecord.setCreateTime(LocalDateTime.now());
        auditRecordMapper.insert(auditRecord);

        assertThat(auditRecord.getId()).isPositive();
        return auditRecord;
    }

    /**
     * 插入一条待投递 outbox 事件，验证 String JSON payload 可以在 PostgreSQL TEXT 列上稳定读写。
     */
    private PermissionEventOutbox insertOutboxEvent(String suffix) {
        PermissionEventOutbox outbox = new PermissionEventOutbox();
        outbox.setEventId("pg-permission-event-" + suffix);
        outbox.setEventType("PERMISSION_POSTGRESQL_INTEGRATION_TEST");
        outbox.setTopic("datasmart.permission.policy.changed");
        outbox.setEventKey("900001");
        outbox.setPayloadJson("{\"event\":\"permission-postgresql-integration-test\"}");
        outbox.setStatus("PENDING");
        outbox.setAttemptCount(0);
        outbox.setMaxAttempts(3);
        outbox.setTenantId(900001L);
        outbox.setResourceType("PERMISSION_MIGRATION");
        outbox.setResourceId("postgresql-baseline");
        outbox.setTraceId("pg-permission-outbox-" + suffix);
        outbox.setCreateTime(LocalDateTime.now());
        outbox.setUpdateTime(LocalDateTime.now());
        eventOutboxMapper.insert(outbox);

        assertThat(outbox.getId()).isPositive();
        return outbox;
    }

    /**
     * 验证 outbox 的核心状态流转。
     *
     * <p>这里刻意不通过 dispatcher 线程，而是直接调用 Mapper：
     * 迁移风险主要在 SQL 方言本身，例如 PostgreSQL 是否接受 LIMIT 参数、时间间隔表达式和条件更新。
     * 只要这些底层 SQL 在真实库上通过，调度器的 Java 流程就仍然可以复用现有单元测试覆盖。</p>
     */
    private void assertOutboxStateMachine(PermissionEventOutbox outbox) {
        List<PermissionEventOutbox> dispatchable = eventOutboxMapper.selectDispatchable(20);
        assertThat(dispatchable).extracting(PermissionEventOutbox::getEventId).contains(outbox.getEventId());

        assertThat(eventOutboxMapper.markSending(outbox.getId())).isEqualTo(1);
        assertThat(eventOutboxMapper.markFailed(outbox.getId(), "PostgreSQL interval integration test", 1)).isEqualTo(1);

        PermissionEventOutbox failed = eventOutboxMapper.selectById(outbox.getId());
        assertThat(failed.getStatus()).isEqualTo("FAILED");
        assertThat(failed.getAttemptCount()).isEqualTo(1);
        assertThat(failed.getNextRetryTime()).isNotNull();

        assertThat(eventOutboxMapper.markSending(outbox.getId())).isEqualTo(1);
        jdbcTemplate.update(
                "UPDATE permission_event_outbox SET update_time = CURRENT_TIMESTAMP - INTERVAL '10 seconds' WHERE id = ?",
                outbox.getId()
        );
        assertThat(eventOutboxMapper.recoverStaleSending(5)).isGreaterThanOrEqualTo(1);

        PermissionEventOutbox recovered = eventOutboxMapper.selectById(outbox.getId());
        assertThat(recovered.getStatus()).isEqualTo("FAILED");
        assertThat(recovered.getLastError()).contains("SENDING timeout");

        assertThat(eventOutboxMapper.markManualRetry(outbox.getId(), "integration-test manual retry")).isEqualTo(1);
        assertThat(eventOutboxMapper.markIgnored(outbox.getId(), "integration-test cleanup ignore")).isEqualTo(1);

        PermissionEventOutbox ignored = eventOutboxMapper.selectById(outbox.getId());
        assertThat(ignored.getStatus()).isEqualTo("IGNORED");
        assertThat(ignored.getAttemptCount()).isZero();
    }

    /**
     * 定向清理本次测试创建的低敏事实。
     */
    private void deleteIntegrationFacts(PermissionAuditRecord auditRecord, PermissionEventOutbox outbox) {
        if (outbox != null && outbox.getId() != null) {
            jdbcTemplate.update("DELETE FROM permission_event_outbox WHERE id = ?", outbox.getId());
        }
        if (auditRecord != null && auditRecord.getId() != null) {
            jdbcTemplate.update("DELETE FROM permission_audit_record WHERE id = ?", auditRecord.getId());
        }
    }
}
