/**
 * @Author : Cui
 * @Date: 2026/08/20 00:00
 * @Description DataSmart Govern Backend - AgentCommandTaskFinalStateCallbackWorkerPrerequisiteValidator.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 最终态 callback worker 的启动前置条件校验器。
 *
 * <p>最终态 callback 会改变 task-management 的任务状态，不能像普通定时任务一样“先启动、运行到第一条
 * SQL 或 HTTP 请求时再发现配置不完整”。本校验器把部署契约提前到启动期：</p>
 * <ol>
 *     <li>必须明确打开 Agent Runtime 数据库持久化；</li>
 *     <li>receipt index 必须使用 PostgreSQL durable store，不能误用 memory 或历史 MySQL 别名；</li>
 *     <li>必须启用专用 Flyway，并且 V8/V9 已成功应用；</li>
 *     <li>receipt、callback job、callback history 三张表必须真实存在。</li>
 * </ol>
 *
 * <p>校验分成两个生命周期阶段：{@link #afterPropertiesSet()} 只检查不需要访问数据库的配置，保证错误配置在
 * worker Bean 创建前就失败；{@link #afterSingletonsInstantiated()} 再检查 Flyway 结果和数据库表，避免在迁移尚未完成
 * 时误判。这里不读取或输出数据库密码、连接串中的凭据和任何业务数据。</p>
 */
@Component("agentCommandTaskFinalStateCallbackWorkerPrerequisiteValidator")
@ConditionalOnProperty(
        prefix = "datasmart.agent-runtime.async-task-final-state-callback-worker",
        name = "enabled",
        havingValue = "true"
)
public class AgentCommandTaskFinalStateCallbackWorkerPrerequisiteValidator
        implements org.springframework.beans.factory.InitializingBean,
        org.springframework.beans.factory.SmartInitializingSingleton {

    /** callback worker 依赖的固定控制面 schema。 */
    static final String REQUIRED_SCHEMA = "agent_runtime";

    /** callback worker 依赖的表名白名单。 */
    static final Set<String> REQUIRED_TABLES = Set.of(
            "agent_tool_action_worker_receipt_index",
            "agent_command_task_final_callback_job",
            "agent_command_task_final_callback_history");

    /** callback worker 依赖的迁移版本；V9 负责把租约时间统一为带时区的绝对时间。 */
    static final Set<String> REQUIRED_MIGRATIONS = Set.of("8", "9");

    private final AgentRuntimePersistenceProperties persistenceProperties;
    private final ObjectProvider<DataSource> dataSourceProvider;
    private final ObjectProvider<Flyway> flywayProvider;
    private final org.springframework.core.env.Environment environment;

    /**
     * 注入配置、运行时连接池和专用 Flyway。
     *
     * <p>使用 {@link ObjectProvider} 是有意的：当配置不满足条件时，连接池或 Flyway 可能根本没有 Bean，
     * 校验器仍要给出“哪一项前置条件缺失”的明确错误，而不是只暴露 Spring 的模糊依赖注入异常。</p>
     */
    public AgentCommandTaskFinalStateCallbackWorkerPrerequisiteValidator(
            AgentRuntimePersistenceProperties persistenceProperties,
            @Qualifier("agentRuntimeJdbcDataSource") ObjectProvider<DataSource> dataSourceProvider,
            @Qualifier("agentRuntimeFlyway") ObjectProvider<Flyway> flywayProvider,
            org.springframework.core.env.Environment environment) {
        this.persistenceProperties = persistenceProperties;
        this.dataSourceProvider = dataSourceProvider;
        this.flywayProvider = flywayProvider;
        this.environment = environment;
    }

    /**
     * 在依赖它的 worker 创建前检查纯配置条件。
     *
     * <p>这些检查不访问网络或数据库，因此错误配置不会因为连接超时而表现成难以定位的启动卡顿。</p>
     */
    @Override
    public void afterPropertiesSet() {
        validateConfiguration();
    }

    /**
     * 在所有单例初始化完成后检查 Flyway 版本和实际表结构。
     *
     * <p>Flyway 的迁移初始化器会在应用进入 ready 前执行；在这个阶段读取它的结果可以区分“文件存在”和“迁移
     * 已经成功落库”，避免仅凭 classpath 文件判断数据库可用。</p>
     */
    @Override
    public void afterSingletonsInstantiated() {
        validateAppliedMigrations();
        validateRequiredTables();
    }

    /**
     * 校验 worker 与 durable 控制面之间的静态配置契约。
     */
    void validateConfiguration() {
        if (!persistenceProperties.isDatabaseEnabled()) {
            throw prerequisiteFailure(
                    "datasmart.agent-runtime.persistence.database-enabled=true",
                    "当前值为 false，callback worker 没有跨重启的 job、租约和幂等事实，不能启动无人值守副作用线程");
        }
        if (!AgentRuntimeStoreMode.isPostgresqlDurable(
                readWorkerReceiptIndexStore())) {
            throw prerequisiteFailure(
                    "datasmart.agent-runtime.tool-action-resume-facts.worker-receipt-index-store=postgresql",
                    "memory 和历史 mysql 别名都不满足 callback worker 的 PostgreSQL 迁移与 SQL 契约");
        }
        if (!environment.getProperty("spring.flyway.enabled", Boolean.class, false)) {
            throw prerequisiteFailure(
                    "spring.flyway.enabled=true",
                    "没有 Flyway 启动迁移，无法证明 V8/V9 的租约表结构已就绪");
        }
        String schema = environment.getProperty("spring.flyway.default-schema", REQUIRED_SCHEMA);
        if (!REQUIRED_SCHEMA.equalsIgnoreCase(schema == null ? "" : schema.trim())) {
            throw prerequisiteFailure(
                    "spring.flyway.default-schema=agent_runtime",
                    "callback worker 的 SQL 固定使用 agent_runtime 控制面 schema");
        }
    }

    /**
     * 检查专用 Flyway 是否真的成功应用 V8 和 V9。
     */
    void validateAppliedMigrations() {
        Flyway flyway = flywayProvider.getIfAvailable();
        if (flyway == null) {
            throw prerequisiteFailure(
                    "Agent Runtime 专用 Flyway Bean",
                    "spring.flyway.enabled=true 但没有创建 agentRuntimeFlyway，迁移目标不明确");
        }
        Set<String> appliedVersions = new HashSet<>();
        MigrationInfo[] applied = flyway.info().applied();
        if (applied != null) {
            Arrays.stream(applied)
                    .filter(info -> info != null && info.getVersion() != null)
                    .map(info -> info.getVersion().getVersion())
                    .forEach(appliedVersions::add);
        }
        if (!appliedVersions.containsAll(REQUIRED_MIGRATIONS)) {
            throw prerequisiteFailure(
                    "Flyway V8/V9 已成功应用",
                    "已应用版本=" + appliedVersions + "；V8 创建 callback durable 表，V9 统一 UTC 租约时间");
        }
    }

    /**
     * 查询 PostgreSQL information_schema，确认迁移不只是写入历史表而且目标表确实可见。
     */
    void validateRequiredTables() {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            throw prerequisiteFailure(
                    "agentRuntimeJdbcDataSource",
                    "数据库开关已打开但没有创建 Agent Runtime 专用连接池");
        }
        String sql = "SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema = ? AND table_name IN (?, ?, ?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, REQUIRED_SCHEMA);
            int index = 2;
            for (String table : REQUIRED_TABLES) {
                statement.setString(index++, table);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                int found = resultSet.next() ? resultSet.getInt(1) : 0;
                if (found != REQUIRED_TABLES.size()) {
                    throw prerequisiteFailure(
                            "agent_runtime callback 表",
                            "实际可见表数量=" + found + "/" + REQUIRED_TABLES.size());
                }
            }
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException(
                    "Agent Runtime callback worker 启动校验无法读取 PostgreSQL schema；请检查数据库连通性、账号权限和 Flyway 迁移状态",
                    exception);
        }
    }

    /**
     * 读取 worker receipt store 配置，兼容 Spring 环境变量覆盖。
     */
    private String readWorkerReceiptIndexStore() {
        return environment.getProperty(
                "datasmart.agent-runtime.tool-action-resume-facts.worker-receipt-index-store",
                "memory");
    }

    /**
     * 构造不包含密码、token 或连接串的启动失败信息。
     */
    private IllegalStateException prerequisiteFailure(String expected, String reason) {
        return new IllegalStateException(
                "Agent Runtime 最终态 callback worker 启动前置条件不满足：需要 " + expected + "；" + reason);
    }
}
