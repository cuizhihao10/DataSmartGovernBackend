/**
 * @Author : Cui
 * @Date: 2026/08/07 00:00
 * @Description DataSmart Govern Backend - SpecialistTurnFactMigrationContractTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.specialist;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 专业 Agent turn 事实 Flyway 迁移的结构契约测试。
 *
 * <p>JDBC 单元测试只能证明当前 SQL 绑定了范围参数，不能防止未来维护迁移时意外删掉某个隔离列、唯一约束或索引。
 * 本测试直接读取 V5，固定六类专业 Agent 共用事实表的最小安全形状：用户与 Agent 双主体、租户/项目边界、
 * session/run 定位、可重试幂等键，以及不再存在的 workspace 层级不会重新进入数据模型。</p>
 */
class SpecialistTurnFactMigrationContractTest {

    /** Agent Runtime 模块内 Flyway V5 的相对路径；Maven 在模块目录执行测试，因此不依赖机器绝对路径。 */
    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/postgresql/agent-runtime/V5__specialist_agent_turn_facts.sql");

    /**
     * V6 必须是增量迁移而不是重写已发布的 V5。生产数据库已经记录 V5 校验和，
     * 任何回写旧迁移的做法都会把一次安全加固变成 Flyway 启动故障。
     */
    private static final Path APPLICATION_SCOPE_MIGRATION = Path.of(
            "src/main/resources/db/migration/postgresql/agent-runtime/V6__specialist_turn_fact_application_scope.sql");

    /**
     * 事实表必须同时保存用户、专业 Agent、租户、项目、会话和 Run，才能在查询时做双主体对象级收口。
     */
    @Test
    void shouldPersistDualSubjectAndTenantProjectSessionRunBoundaries() throws IOException {
        String sql = migrationSql();

        assertThat(sql)
                .contains("CREATE TABLE IF NOT EXISTS agent_specialist_turn_fact")
                .contains("user_id VARCHAR(128) NOT NULL")
                .contains("tenant_id BIGINT NOT NULL")
                .contains("project_id BIGINT NOT NULL")
                .contains("session_id VARCHAR(160) NOT NULL")
                .contains("run_id VARCHAR(160) NOT NULL")
                .contains("agent_id VARCHAR(160) NOT NULL")
                .contains("delegation_id VARCHAR(256)")
                .contains("CONSTRAINT ck_agent_specialist_turn_fact_scope CHECK (tenant_id > 0 AND project_id > 0)")
                .doesNotContain("workspace_id");
    }

    /**
     * 重试键与 turn 身份都必须不可跨主体复用，避免一条专业事实被覆盖成另一个用户或 Agent 的执行结果。
     */
    @Test
    void shouldKeepIdempotencyAndImmutableTurnIdentityConstraints() throws IOException {
        String sql = migrationSql();

        assertThat(sql)
                .contains("CONSTRAINT uk_agent_specialist_turn_fact_idempotency UNIQUE (idempotency_key)")
                .contains("CONSTRAINT uk_agent_specialist_turn_fact_turn UNIQUE (session_id, run_id, turn_id)")
                .contains("CONSTRAINT ck_agent_specialist_turn_fact_identifiers")
                .contains("CONSTRAINT ck_agent_specialist_turn_fact_duration")
                .contains("CONSTRAINT ck_agent_specialist_turn_fact_time_order");
    }

    /**
     * session/run 读取索引必须把 tenant/project 放在前缀，普通用户读取还必须包含 user_id，防止范围条件退化为全表扫描。
     */
    @Test
    void shouldKeepScopedSessionAndRunReadIndexes() throws IOException {
        String sql = migrationSql();

        assertThat(sql)
                .contains("idx_agent_specialist_turn_fact_scope_session")
                .contains("(tenant_id, project_id, user_id, session_id, updated_at DESC)")
                .contains("idx_agent_specialist_turn_fact_scope_run")
                .contains("(tenant_id, project_id, user_id, run_id, updated_at DESC)")
                .contains("idx_agent_specialist_turn_fact_project_session")
                .contains("idx_agent_specialist_turn_fact_project_run");
    }

    /**
     * 应用层绑定采用前向迁移，并只从 permission_project 这份权威项目主数据回填历史记录。
     * 无法证实归属的旧记录保留空 applicationId 且被 Java 查询拒绝，而不是猜测某个应用后放宽隔离。
     */
    @Test
    void shouldAddApplicationScopeWithForwardOnlyFailClosedMigration() throws IOException {
        String sql = Files.readString(APPLICATION_SCOPE_MIGRATION);

        assertThat(sql)
                .contains("ADD COLUMN IF NOT EXISTS application_id BIGINT")
                .contains("permission_admin.permission_project")
                .contains("fact.tenant_id = project.tenant_id")
                .contains("fact.project_id = project.project_id")
                .contains("CHECK (application_id IS NOT NULL AND application_id > 0) NOT VALID")
                .contains("idx_agent_specialist_turn_fact_application_session")
                .contains("idx_agent_specialist_turn_fact_application_run")
                .doesNotContain("DROP COLUMN")
                .doesNotContain("workspace_id");
    }

    /**
     * 读取迁移正文并让 IOException 明确冒泡给测试框架。
     *
     * <p>迁移文件丢失与迁移内容被错误重命名都应该是构建失败，而不是被测试静默忽略。这样 CI 可以在 Flyway
     * 真正执行到生产数据库之前，先发现与 Java 控制面安全假设不一致的结构变更。</p>
     */
    private String migrationSql() throws IOException {
        return Files.readString(MIGRATION);
    }
}
