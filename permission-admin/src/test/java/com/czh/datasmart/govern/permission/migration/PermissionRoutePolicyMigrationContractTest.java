package com.czh.datasmart.govern.permission.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PermissionRoutePolicyMigrationContractTest {

    private static final Path MIGRATION_DIRECTORY = Path.of(
            "src/main/resources/db/migration/postgresql/permission-admin");

    @Test
    void routePolicyMigrationsUseBaselineColumnNames() throws IOException {
        List<Path> invalidMigrations;
        try (var paths = Files.list(MIGRATION_DIRECTORY)) {
            invalidMigrations = paths
                    .filter(path -> path.getFileName().toString().matches("V(4[0-9]|[5-9][0-9])__.*\\.sql"))
                    .filter(this::containsLegacyRoutePolicyColumn)
                    .toList();
        }

        assertThat(invalidMigrations)
                .as("permission_route_policy uses action/create_time/update_time from the V1 baseline")
                .isEmpty();
    }

    @Test
    void agentCancellationMigrationGrantsEveryInteractiveAgentRole() throws IOException {
        String sql = Files.readString(MIGRATION_DIRECTORY.resolve(
                "V45__agent_plan_cancellation_route_policy.sql"));

        assertThat(sql)
                .contains("'/api/agent/plans/cancel'")
                .contains("'CANCEL_INFERENCE'")
                .contains("'ORDINARY_USER'")
                .contains("'PROJECT_OWNER'");
    }

    /**
     * 专业事实的 session/run 查询必须拥有独立的 VIEW 路由。
     *
     * <p>如果只依赖 {@code /api/agent/**}，Gateway 或 permission-admin 后续新增 Agent 写接口时，
     * 很容易把事实读取误判成普通 Agent 操作。这里把两个定位语义固定在迁移契约中，确保 Flyway
     * 版本推进后仍然能够从权限数据库解释“查 session”和“查 run”是同一类低敏读取动作。</p>
     */
    @Test
    void specialistTurnFactMigrationDefinesDedicatedSessionAndRunViewRoutes() throws IOException {
        String sql = Files.readString(MIGRATION_DIRECTORY.resolve(
                "V48__specialist_agent_turn_fact_route_policy.sql"));

        assertThat(sql)
                .contains("'/api/agent/specialist-turn-facts/sessions/*'")
                .contains("'/api/agent/specialist-turn-facts/runs/*'")
                .contains("'AI_RUNTIME'")
                .contains("'VIEW'")
                .contains("'ORDINARY_USER'")
                .contains("'PROJECT_OWNER'")
                .doesNotContain("workspace_id")
                .doesNotContain("WORKSPACE");
    }

    /**
     * 专业事实登记必须是服务账号专用的 EXECUTE 动作，并对人类角色保留高优先级 DENY。
     *
     * <p>“没有匹配策略即拒绝”不是足够稳定的长期合同，因为未来可能加入平台级通配 ALLOW。
     * 显式 DENY 让普通用户即使直接构造 POST、伪造 APPROVED 状态或填入任意模型名，也不能通过权限中心入口。</p>
     */
    @Test
    void specialistTurnFactRegistrationIsServiceOnlyAndHumanRolesAreDenied() throws IOException {
        String sql = Files.readString(MIGRATION_DIRECTORY.resolve(
                "V48__specialist_agent_turn_fact_route_policy.sql"));

        assertThat(sql)
                .contains("'/api/agent/specialist-turn-facts'")
                .contains("'SERVICE_ACCOUNT'")
                .contains("'EXECUTE'")
                .contains("'DENY'")
                .contains("'/api/agent/specialist-turn-facts/**'")
                .contains("('ORDINARY_USER')")
                .contains("('PROJECT_OWNER')")
                .contains("('PLATFORM_ADMINISTRATOR')")
                .doesNotContain("workspace_id")
                .doesNotContain("WORKSPACE");
    }

    /**
     * 固定 V48 的管理员 VIEW 决策，避免后续维护者把角色名称误读成专业事实的自动全量权限。
     *
     * <p>平台管理员的既有 {@code /api/**} GET 兜底由权限判定单测覆盖；本迁移只负责普通用户和
     * 项目负责人的 SELF VIEW。租户管理员、审计员和运营员若要查看跨用户事实，必须新增专用审计
     * 路由及明确的数据范围，而不能复用当前用户入口。</p>
     */
    @Test
    void specialistTurnFactViewDoesNotImplicitlyExpandAdministrativeRoles() throws IOException {
        String sql = Files.readString(MIGRATION_DIRECTORY.resolve(
                "V48__specialist_agent_turn_fact_route_policy.sql"));

        assertThat(sql)
                .doesNotContain("'TENANT_ADMINISTRATOR', 'GET'")
                .doesNotContain("'AUDITOR', 'GET'")
                .doesNotContain("'OPERATOR', 'GET'")
                .doesNotContain("'PLATFORM_ADMINISTRATOR', 'GET'");
    }

    /**
     * Agent Console 的知识查询与事件补偿入口必须有显式人类主体策略，不能依赖通配默认动作。
     */
    @Test
    void agentConsoleRagAndRuntimeEventRoutesHaveExplicitPolicies() throws IOException {
        String sql = Files.readString(MIGRATION_DIRECTORY.resolve(
                "V50__agent_console_rag_event_route_policy.sql"));

        assertThat(sql)
                .contains("'/api/agent/rag/query'")
                .contains("'/api/agent/rag/diagnostics'")
                .contains("'/api/agent/events/replay'")
                .contains("'/api/agent/events/control'")
                .contains("'/api/agent/models/routes'")
                .contains("'/api/agent/tools/**'")
                .contains("'ORDINARY_USER'")
                .contains("'PROJECT_OWNER'")
                .contains("'AUDITOR'")
                .contains("'OPERATOR'")
                .contains("'AI_RUNTIME'")
                .doesNotContain("workspace_id")
                .doesNotContain("WORKSPACE");
    }

    @Test
    void websocketRoutePolicyMustMatchGatewayGetSubscribeContract() throws IOException {
        String sql = Files.readString(MIGRATION_DIRECTORY.resolve(
                "V51__agent_console_websocket_route_policy.sql"));

        assertThat(sql)
                .contains("'/api/agent/events/ws'")
                .contains("'GET'")
                .contains("'SUBSCRIBE'")
                .contains("'ORDINARY_USER'")
                .contains("'PROJECT_OWNER'")
                .contains("'OPERATOR'")
                .contains("'AUDITOR'")
                .contains("ON CONFLICT DO NOTHING");
    }

    /** Checkpoint 低敏查询必须有显式 VIEW_CHECKPOINT 策略，控制写路由不能顺带开放。 */
    @Test
    void langGraphCheckpointReadPoliciesMustStayReadOnlyAndExplicit() throws IOException {
        String sql = Files.readString(MIGRATION_DIRECTORY.resolve(
                "V52__langgraph_checkpoint_read_route_policy.sql"));

        assertThat(sql)
                .contains("'/api/agent/langgraph/checkpoints/latest'")
                .contains("'/api/agent/langgraph/checkpoints/events'")
                .contains("'GET'")
                .contains("'VIEW_CHECKPOINT'")
                .contains("'ORDINARY_USER'")
                .contains("'PROJECT_OWNER'")
                .contains("'OPERATOR'")
                .contains("'AUDITOR'")
                .doesNotContain("'/api/agent/langgraph/checkpoints/pause'")
                .doesNotContain("'/api/agent/langgraph/checkpoints/resume'")
                .doesNotContain("'/api/agent/langgraph/checkpoints/fork'");
    }

    private boolean containsLegacyRoutePolicyColumn(Path path) {
        try {
            String sql = Files.readString(path);
            return sql.contains("action_code")
                    || sql.contains("created_at")
                    || sql.contains("updated_at");
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read migration " + path, exception);
        }
    }
}
