/**
 * @Author : Cui
 * @Date: 2026/07/03 03:00
 * @Description DataSmart Govern Backend - AgentRuntimeFlywayMigrationConfiguration.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.config;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Agent Runtime PostgreSQL Flyway 显式迁移配置。
 *
 * <p>为什么这里需要显式配置，而不是完全依赖 Spring Boot Flyway 自动配置：</p>
 * <p>1. agent-runtime 为了兼容本地学习和 memory 模式，没有启用全局 {@code spring.datasource}；</p>
 * <p>2. 控制面 JDBC 连接池使用 {@code datasmart.agent-runtime.persistence.jdbc.*} 独立配置，避免被其他业务模块 DataSource 污染；</p>
 * <p>3. Spring Boot 的 Flyway 自动配置在没有标准 DataSource 的场景下容易被条件装配跳过，导致容器健康但表结构没有创建；</p>
 * <p>4. 本类把 Flyway 与 Agent Runtime 专用 JDBC 配置绑定，确保迁移脚本、运行时 store、容器环境变量指向同一套 PostgreSQL 目标。</p>
 *
 * <p>启用条件：</p>
 * <p>只有 {@code spring.flyway.enabled=true} 时才注册。默认 memory 模式仍不创建数据库连接、不执行迁移，保证没有 PostgreSQL 的开发者也能启动服务和运行单元测试。</p>
 *
 * <p>商业化约束：</p>
 * <p>当容器、集成测试或生产环境显式启用 Flyway 后，迁移失败必须阻断启动。否则 agent-runtime 可能在缺表或旧表结构上继续运行，
 * 造成 outbox、worker receipt、恢复定位符、artifact grant 等控制面事实写入失败，最终影响 Agent 恢复、审计和幂等。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.flyway", name = "enabled", havingValue = "true")
public class AgentRuntimeFlywayMigrationConfiguration {

    private static final String DEFAULT_SCHEMA = "agent_runtime";
    private static final String DEFAULT_LOCATION = "classpath:db/migration/postgresql/agent-runtime";

    /**
     * 创建 Agent Runtime 专用 Flyway 实例。
     *
     * <p>该 Bean 不复用全局 {@code spring.datasource}，而是复用 {@link AgentRuntimePersistenceProperties.Jdbc}。</p>
     * <p>这样做的设计意图是：迁移在哪里跑，运行时 JDBC store 就在哪里写；如果二者使用不同 URL，最危险的情况不是启动失败，
     * 而是 Flyway 在 A 数据库建表、业务 store 在 B 数据库写入，然后运行到第一条 SQL 才暴露问题。</p>
     *
     * @param properties Agent Runtime 专用持久化配置，包含 URL、用户名、密码和 driver。
     * @param environment Spring Environment，用于读取少量 spring.flyway.* 可覆盖参数。
     * @return 已配置但尚未执行迁移的 Flyway 实例。
     */
    @Bean(name = "agentRuntimeFlyway")
    public Flyway agentRuntimeFlyway(AgentRuntimePersistenceProperties properties, Environment environment) {
        AgentRuntimePersistenceProperties.Jdbc jdbc = properties.getJdbc();
        String schema = environment.getProperty("spring.flyway.default-schema", DEFAULT_SCHEMA);
        String location = environment.getProperty("spring.flyway.locations", DEFAULT_LOCATION);
        boolean validateOnMigrate = environment.getProperty("spring.flyway.validate-on-migrate", Boolean.class, true);
        boolean cleanDisabled = environment.getProperty("spring.flyway.clean-disabled", Boolean.class, true);
        boolean baselineOnMigrate = environment.getProperty("spring.flyway.baseline-on-migrate", Boolean.class, false);
        boolean createSchemas = environment.getProperty("spring.flyway.create-schemas", Boolean.class, false);

        return Flyway.configure()
                .dataSource(
                        requireText(jdbc.getUrl(), "datasmart.agent-runtime.persistence.jdbc.url"),
                        requireText(jdbc.getUsername(), "datasmart.agent-runtime.persistence.jdbc.username"),
                        jdbc.getPassword() == null ? "" : jdbc.getPassword())
                .driver(requireText(jdbc.getDriverClassName(), "datasmart.agent-runtime.persistence.jdbc.driver-class-name"))
                .schemas(requireText(schema, "spring.flyway.default-schema"))
                .defaultSchema(requireText(schema, "spring.flyway.default-schema"))
                .locations(requireText(location, "spring.flyway.locations"))
                .validateOnMigrate(validateOnMigrate)
                .cleanDisabled(cleanDisabled)
                .baselineOnMigrate(baselineOnMigrate)
                .createSchemas(createSchemas)
                .load();
    }

    /**
     * 在 Spring 容器启动期执行 Flyway migrate。
     *
     * <p>{@link FlywayMigrationInitializer} 是 Spring Boot 官方提供的初始化器。相比在 {@code ApplicationRunner} 中手动调用
     * {@code flyway.migrate()}，它发生得更早，更符合“表结构未准备好就不应对外提供健康服务”的生产原则。</p>
     *
     * @param flyway Agent Runtime 专用 Flyway 实例。
     * @return Flyway 迁移初始化器，创建 Bean 时会执行 migrate。
     */
    @Bean(name = "agentRuntimeFlywayMigrationInitializer")
    public FlywayMigrationInitializer agentRuntimeFlywayMigrationInitializer(
            @Qualifier("agentRuntimeFlyway") Flyway flyway) {
        return new FlywayMigrationInitializer(flyway);
    }

    private String requireText(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("启用 Agent Runtime Flyway/PostgreSQL 迁移时必须配置 " + propertyName);
        }
        return value.trim();
    }
}
