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
import com.czh.datasmart.govern.permission.controller.dto.PermissionDecisionRequest;
import com.czh.datasmart.govern.permission.mapper.PermissionAuditRecordMapper;
import com.czh.datasmart.govern.permission.mapper.PermissionEventOutboxMapper;
import com.czh.datasmart.govern.permission.mapper.PermissionRoleMapper;
import com.czh.datasmart.govern.permission.service.support.PermissionDecisionSupport;
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
    private final PermissionDecisionSupport decisionSupport;

    @Autowired
    PermissionAdminPostgreSqlMigrationIntegrationTest(
            JdbcTemplate jdbcTemplate,
            PermissionRoleMapper roleMapper,
            PermissionAuditRecordMapper auditRecordMapper,
            PermissionEventOutboxMapper eventOutboxMapper,
            PermissionDecisionSupport decisionSupport) {
        this.jdbcTemplate = jdbcTemplate;
        this.roleMapper = roleMapper;
        this.auditRecordMapper = auditRecordMapper;
        this.eventOutboxMapper = eventOutboxMapper;
        this.decisionSupport = decisionSupport;
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
        assertFlashSyncTenantBootstrap();
        assertAgentSessionHistoryRoutePolicies();
        assertSpecialistTurnFactRoutePolicies();
        assertSpecialistTurnFactDecisions();

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
                      'permission_event_outbox',
                      'permission_identity_user',
                      'permission_tenant',
                      'permission_application',
                      'permission_project',
                      'permission_workspace'
                  )
                """, Integer.class);

        assertThat(currentSchema).isEqualTo("permission_admin");
        assertThat(flywaySuccessCount).isEqualTo(1);
        assertThat(tableCount).isEqualTo(13);
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
     * 验证 FlashSync 开租基线是否随 PostgreSQL 迁移一起落库。
     *
     * <p>这不是普通的“有没有插入样例数据”检查，而是在保护一条产品级约定：
     * tenantId、applicationId、projectId、workspaceId 应由平台开租流程生成，
     * 业务用户不应该在创建同步任务时手工猜这些内部 ID。</p>
     */
    private void assertFlashSyncTenantBootstrap() {
        String tenantName = jdbcTemplate.queryForObject(
                "SELECT tenant_name FROM permission_tenant WHERE tenant_id = 10 AND tenant_code = 'FLASHSYNC'",
                String.class
        );
        String applicationName = jdbcTemplate.queryForObject(
                "SELECT application_name FROM permission_application WHERE application_id = 10010 AND tenant_id = 10",
                String.class
        );
        String workspaceKey = jdbcTemplate.queryForObject(
                "SELECT external_workspace_key FROM permission_workspace WHERE workspace_id = 10001 AND tenant_id = 10",
                String.class
        );
        List<Long> projectOwnerProjectIds = jdbcTemplate.queryForList("""
                SELECT project_id
                FROM permission_project_membership
                WHERE tenant_id = 10
                  AND actor_id = 1001
                  AND enabled = true
                """, Long.class);
        Integer shadowIdentityCount = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM permission_identity_user
                WHERE tenant_id = 10
                  AND actor_id IN (1001, 1002, 1003, 1004, 9101)
                  AND status = 'ACTIVE'
                """, Integer.class);
        String ordinaryUserRole = jdbcTemplate.queryForObject("""
                SELECT actor_role
                FROM permission_identity_user
                WHERE tenant_id = 10
                  AND actor_id = 1004
                  AND username = 'ordinary-user'
                """, String.class);
        List<Long> ordinaryUserProjectIds = jdbcTemplate.queryForList("""
                SELECT project_id
                FROM permission_project_membership
                WHERE tenant_id = 10
                  AND actor_id = 1004
                  AND project_role = 'READER'
                  AND enabled = true
                """, Long.class);

        assertThat(tenantName).isEqualTo("FlashSync");
        assertThat(applicationName).isEqualTo("FlashSync");
        assertThat(workspaceKey).isEqualTo("workspace-a");
        assertThat(projectOwnerProjectIds).contains(101L);
        assertThat(shadowIdentityCount).isEqualTo(5);
        assertThat(ordinaryUserRole).isEqualTo("ORDINARY_USER");
        assertThat(ordinaryUserProjectIds).contains(101L);
    }

    /**
     * 验证交互式用户能够从页面恢复、置顶和归档自己的持久化 Agent 会话。
     *
     * <p>该断言保护的是一个容易被忽略的跨服务契约：agent-runtime 即使已经正确保存了会话，
     * 如果 permission-admin 没有相应路由策略，gateway 的 fail-closed 授权仍会拒绝请求，
     * 前端最终只能显示“暂无历史会话”。这里同时检查普通用户和项目负责人四种页面动作，
     * 并要求资源类型保持为 AI_RUNTIME。真正的数据隔离仍由 V35 的 SELF 数据范围和
     * agent-runtime 的 tenant/project/actor 对象归属校验共同完成。</p>
     */
    private void assertAgentSessionHistoryRoutePolicies() {
        Integer enabledPolicyCount = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM permission_route_policy
                WHERE tenant_id = 0
                  AND role_code IN ('ORDINARY_USER', 'PROJECT_OWNER')
                  AND resource_type = 'AI_RUNTIME'
                  AND enabled = true
                  AND (
                      (http_method = 'GET' AND path_pattern IN (
                          '/api/agent/sessions',
                          '/api/agent/sessions/*'
                      ))
                      OR
                      (http_method = 'PATCH' AND path_pattern IN (
                          '/api/agent/sessions/*/pin',
                          '/api/agent/sessions/*/archive'
                      ))
                  )
                """, Integer.class);

        assertThat(enabledPolicyCount).isEqualTo(8);
    }

    /**
     * 在真实 PostgreSQL 上验证专业 Agent turn 事实的路由策略和数据范围。
     *
     * <p>这条断言同时保护四个跨服务契约：普通用户和项目负责人可以查看本人 session/run，
     * SERVICE_ACCOUNT 才能进入事实登记动作，人类角色的登记动作存在高优先级 DENY，且
     * AI_RUNTIME 仍然使用 V35/V48 约定的 SELF 范围。真正的 source-service、共享 token 和
     * 事实字段一致性由 agent-runtime 继续校验；permission-admin 这里只验证 Flyway 落库的
     * 路由和范围事实没有缺失或被错误动作覆盖。</p>
     */
    private void assertSpecialistTurnFactRoutePolicies() {
        Integer interactiveReadPolicyCount = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM permission_route_policy
                WHERE tenant_id = 0
                  AND role_code IN ('ORDINARY_USER', 'PROJECT_OWNER')
                  AND http_method = 'GET'
                  AND path_pattern IN (
                      '/api/agent/specialist-turn-facts/sessions/*',
                      '/api/agent/specialist-turn-facts/runs/*'
                  )
                  AND resource_type = 'AI_RUNTIME'
                  AND action = 'VIEW'
                  AND effect = 'ALLOW'
                  AND enabled = true
                """, Integer.class);
        assertThat(interactiveReadPolicyCount).isEqualTo(4);

        Integer trustedRegistrationPolicyCount = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM permission_route_policy
                WHERE tenant_id = 0
                  AND role_code = 'SERVICE_ACCOUNT'
                  AND http_method = 'POST'
                  AND path_pattern = '/api/agent/specialist-turn-facts'
                  AND resource_type = 'AI_RUNTIME'
                  AND action = 'EXECUTE'
                  AND effect = 'ALLOW'
                  AND enabled = true
                """, Integer.class);
        assertThat(trustedRegistrationPolicyCount).isEqualTo(1);

        Integer humanRegistrationDenyCount = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM permission_route_policy
                WHERE tenant_id = 0
                  AND role_code IN (
                      'ORDINARY_USER', 'PROJECT_OWNER', 'OPERATOR',
                      'AUDITOR', 'TENANT_ADMINISTRATOR', 'PLATFORM_ADMINISTRATOR'
                  )
                  AND http_method = 'POST'
                  AND path_pattern = '/api/agent/specialist-turn-facts/**'
                  AND resource_type = 'AI_RUNTIME'
                  AND action = 'EXECUTE'
                  AND effect = 'DENY'
                  AND enabled = true
                """, Integer.class);
        assertThat(humanRegistrationDenyCount).isEqualTo(6);

        Integer selfScopeCount = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM permission_data_scope_policy
                WHERE tenant_id = 0
                  AND role_code IN ('ORDINARY_USER', 'PROJECT_OWNER')
                  AND resource_type = 'AI_RUNTIME'
                  AND scope_level = 'SELF'
                  AND scope_expression = 'actor_id = ${actorId} AND project_id IN ${actorProjectIds}'
                  AND enabled = true
                """, Integer.class);
        assertThat(selfScopeCount).isEqualTo(2);
    }

    /**
     * 在已经执行完全部 Flyway 迁移的真实 PostgreSQL 上走一遍权限判定服务。
     *
     * <p>这里不能只查询 V48 是否插入了几行 SQL，因为“表里存在策略”和“策略引擎在真实数据上
     * 选中了正确策略”是两件不同的事情。本方法通过 Spring 容器中的
     * {@link PermissionDecisionSupport} 读取当前数据库策略，实际执行 PathPattern、方法、资源、
     * 动作、优先级和数据范围判定，并检查它写入的权限审计结果。这样可以同时验证：
     * <ul>
     *     <li>普通用户和项目负责人可以在 SELF 范围查看自己的专业事实；</li>
     *     <li>运营员、审计员和租户管理员不会因为角色较高而自动得到专业事实 VIEW；</li>
     *     <li>平台管理员保留既有 /api/** GET 入口，但没有被凭角色名扩大 AI_RUNTIME 对象范围；</li>
     *     <li>六类人类角色访问 POST 基路径和其子路径都会命中 V48 的 /** DENY；</li>
     *     <li>SERVICE_ACCOUNT 只有 POST 基路径的专用 EXECUTE allow 可以通过。</li>
     * </ul>
     * </p>
     */
    private void assertSpecialistTurnFactDecisions() {
        String auditTracePrefix = "integration-specialist-v48-";
        try {
            assertThat(decisionSupport.evaluate(
                    specialistFactRequest("ORDINARY_USER", 1004L, "GET",
                            "/api/agent/specialist-turn-facts/sessions/integration-session", "VIEW"),
                    auditTracePrefix + "ordinary-view").getAllowed())
                    .as("ORDINARY_USER 应可查看本人专业 session 事实")
                    .isTrue();

            var projectOwnerView = decisionSupport.evaluate(
                    specialistFactRequest("PROJECT_OWNER", 1001L, "GET",
                            "/api/agent/specialist-turn-facts/runs/integration-run", "VIEW"),
                    auditTracePrefix + "project-owner-view");
            assertThat(projectOwnerView.getAllowed())
                    .as("PROJECT_OWNER 应可查看本人专业 run 事实")
                    .isTrue();
            assertThat(projectOwnerView.getDataScopeLevel()).isEqualTo("SELF");
            assertThat(projectOwnerView.getAuthorizedProjectIds()).contains(101L);

            for (String role : List.of("OPERATOR", "AUDITOR", "TENANT_ADMINISTRATOR")) {
                var result = decisionSupport.evaluate(
                        specialistFactRequest(role, 1002L, "GET",
                                "/api/agent/specialist-turn-facts/runs/integration-run", "VIEW"),
                        auditTracePrefix + role.toLowerCase() + "-view");
                assertThat(result.getAllowed())
                        .as("%s 不应自动获得专业事实 VIEW", role)
                        .isFalse();
            }

            var platformView = decisionSupport.evaluate(
                    specialistFactRequest("PLATFORM_ADMINISTRATOR", 9001L, "GET",
                            "/api/agent/specialist-turn-facts/sessions/integration-session", "VIEW"),
                    auditTracePrefix + "platform-view");
            assertThat(platformView.getAllowed())
                    .as("PLATFORM_ADMINISTRATOR 应保留既有通用 Agent GET 入口")
                    .isTrue();
            assertThat(platformView.getDataScopeLevel())
                    .as("平台通用兜底不能凭角色名生成 AI_RUNTIME 对象范围")
                    .isNull();
            assertThat(platformView.getAuthorizedProjectIds()).isEmpty();

            for (String role : List.of(
                    "ORDINARY_USER",
                    "PROJECT_OWNER",
                    "OPERATOR",
                    "AUDITOR",
                    "TENANT_ADMINISTRATOR",
                    "PLATFORM_ADMINISTRATOR")) {
                for (String path : List.of(
                        "/api/agent/specialist-turn-facts",
                        "/api/agent/specialist-turn-facts/child")) {
                    var result = decisionSupport.evaluate(
                            specialistFactRequest(role, 1004L, "POST", path, "EXECUTE"),
                            auditTracePrefix + role.toLowerCase() + "-register-"
                                    + (path.endsWith("child") ? "child" : "root"));
                    assertThat(result.getAllowed())
                            .as("%s POST %s 必须命中 /** DENY", role, path)
                            .isFalse();
                    assertThat(result.getRouteEffect())
                            .as("%s POST %s 应返回显式 DENY", role, path)
                            .isEqualTo("DENY");
                }
            }

            var serviceRegistration = decisionSupport.evaluate(
                    specialistFactRequest("SERVICE_ACCOUNT", 9101L, "POST",
                            "/api/agent/specialist-turn-facts", "EXECUTE"),
                    auditTracePrefix + "service-register-root");
            assertThat(serviceRegistration.getAllowed())
                    .as("SERVICE_ACCOUNT POST 基路径应命中专用 EXECUTE allow")
                    .isTrue();
            assertThat(serviceRegistration.getRouteEffect()).isEqualTo("ALLOW");
            assertThat(serviceRegistration.getDataScopeLevel()).isEqualTo("TENANT");
        } finally {
            /*
             * evaluate 会按生产逻辑写入权限审计。集成测试必须验证这条副作用，但不能把临时
             * trace 留在共享开发库中，因此只按本方法专用前缀清理，绝不使用 TRUNCATE 或 Flyway clean。
             */
            jdbcTemplate.update(
                    "DELETE FROM permission_audit_record WHERE trace_id LIKE ?",
                    auditTracePrefix + "%");
        }
    }

    /**
     * 构造与 Gateway -> permission-admin 契约一致的专业事实判定请求。
     *
     * <p>测试故意同时传入 HTTP 方法、完整请求路径、AI_RUNTIME 和业务动作，避免只调用
     * 某个 SQL 查询而绕过真正的路由策略选择。请求没有伪造 workspace 字段，也不把 runId
     * 当成授权凭据，项目级归属仍由 permission_project_membership 和 SELF 策略计算。</p>
     */
    private PermissionDecisionRequest specialistFactRequest(String role,
                                                             Long actorId,
                                                             String method,
                                                             String path,
                                                             String action) {
        PermissionDecisionRequest request = new PermissionDecisionRequest();
        request.setTenantId(10L);
        request.setActorId(actorId);
        request.setActorRole(role);
        request.setHttpMethod(method);
        request.setRequestPath(path);
        request.setResourceType("AI_RUNTIME");
        request.setAction(action);
        return request;
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
