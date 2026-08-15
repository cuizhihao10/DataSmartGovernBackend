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

    /**
     * 历史工具码 {@code workspace.text.search} 的业务语义是仓库文本检索，必须登记为独立权限码。
     *
     * <p>该回归用例同时固定三条容易在后续迁移中被误改的边界：策略复用现有 {@code AI_RUNTIME}
     * 数据范围、只开放给交互式普通用户和项目负责人，以及发生唯一键冲突时保留管理员已调整的策略。
     * 这样工具显示名或实现位置改变时，权限码仍可作为稳定的控制面契约。</p>
     */
    @Test
    void repositoryTextSearchPermissionUsesExactCodeAndNeverOverwritesExistingPolicies() throws IOException {
        String sql = Files.readString(MIGRATION_DIRECTORY.resolve(
                "V54__repository_text_search_agent_permission_policy.sql"));

        assertThat(sql)
                .contains("workspace.text.search")
                .contains("agent:repository-text:search")
                .contains("'/internal/agent-runtime/tools/repository-text/search'")
                .contains("'AI_RUNTIME'")
                .contains("'ORDINARY_USER'")
                .contains("'PROJECT_OWNER'")
                .contains("WHERE NOT EXISTS")
                .contains("ON CONFLICT DO NOTHING")
                .doesNotContain("DO UPDATE")
                .doesNotContain("DELETE FROM")
                .doesNotContain("workspace_id")
                .doesNotContain("WORKSPACE");
    }

    /** Autopilot recovery status is an execution VIEW contract, never a callback or recovery mutation. */
    @Test
    void autopilotRecoveryStatusRouteIsExplicitlyReadOnly() throws IOException {
        String sql = Files.readString(MIGRATION_DIRECTORY.resolve(
                "V55__data_sync_autopilot_recovery_status_route_policy.sql"));

        assertThat(sql)
                .contains("'/api/sync/sync-tasks/*/executions/*/autopilot-recovery'")
                .contains("'SYNC_EXECUTION'")
                .contains("'VIEW'")
                .contains("'PROJECT_OWNER'")
                .contains("'OPERATOR'")
                .contains("'AUDITOR'")
                .contains("'TENANT_ADMINISTRATOR'")
                .doesNotContain("'RECOVER'")
                .doesNotContain("'CALLBACK'");
    }

    /**
     * 统一生命周期图必须在 permission-admin 中拥有精确的只读策略。
     *
     * <p>Gateway 的本地路由元数据只能决定“应当按什么资源和动作询问权限中心”，不能代替权限中心的
     * 最终 ALLOW 策略。若迁移缺失，真实用户即使能够查看同步任务，也会被 fail-closed 拒绝。该合同同时
     * 禁止把执行、恢复或 worker 回调动作混入图查询策略。</p>
     */
    @Test
    void executionLifecycleGraphRouteIsExplicitlyReadOnly() throws IOException {
        String sql = Files.readString(MIGRATION_DIRECTORY.resolve(
                "V56__data_sync_execution_lifecycle_graph_route_policy.sql"));

        assertThat(sql)
                .contains("'/api/sync/sync-tasks/*/executions/*/lifecycle-graph'")
                .contains("'SYNC_EXECUTION'")
                .contains("'GET'")
                .contains("'VIEW'")
                .contains("'ORDINARY_USER'")
                .contains("'PROJECT_OWNER'")
                .contains("'OPERATOR'")
                .contains("'AUDITOR'")
                .contains("'TENANT_ADMINISTRATOR'")
                .contains("'PLATFORM_ADMINISTRATOR'")
                .contains("ON CONFLICT DO NOTHING")
                .doesNotContain("'POST'")
                .doesNotContain("'EXECUTE'")
                .doesNotContain("'RECOVER'")
                .doesNotContain("'CALLBACK'");
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
