/**
 * @Author : Cui
 * @Date: 2026/08/20 00:00
 * @Description DataSmart Govern Backend - AgentCommandTaskFinalStateCallbackWorkerPrerequisiteValidatorTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * callback worker 启动前置条件的最小 Spring 上下文回归。
 *
 * <p>这些测试刻意不启动完整 Agent Runtime、PostgreSQL 或定时线程。它们只验证一个生产关键行为：部署人员一旦
 * 打开有副作用的 callback worker，缺少 durable 数据库、PostgreSQL receipt store、Flyway 或目标表时，应用上下文
 * 必须明确失败；条件齐全时才允许继续启动。</p>
 */
class AgentCommandTaskFinalStateCallbackWorkerPrerequisiteValidatorTest {

    private final ApplicationContextRunner baseRunner = new ApplicationContextRunner()
            .withUserConfiguration(ValidatorOnlyConfiguration.class)
            .withPropertyValues(
                    "datasmart.agent-runtime.async-task-final-state-callback-worker.enabled=true",
                    "spring.flyway.default-schema=agent_runtime");

    /**
     * 数据库总开关未打开时必须在创建 worker 前失败，不能回退到内存 job store。
     */
    @Test
    void shouldFailStartupWhenDatabasePersistenceIsDisabled() {
        baseRunner
                .withPropertyValues(
                        "datasmart.agent-runtime.persistence.database-enabled=false",
                        "datasmart.agent-runtime.tool-action-resume-facts.worker-receipt-index-store=postgresql",
                        "spring.flyway.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootCauseMessage(context.getStartupFailure()))
                            .contains("persistence.database-enabled=true");
                });
    }

    /**
     * receipt 仍在 memory 时必须失败；历史 mysql 字符串也不能冒充 PostgreSQL durable 模式。
     */
    @Test
    void shouldFailStartupWhenReceiptStoreIsNotPostgresqlDurable() {
        baseRunner
                .withPropertyValues(
                        "datasmart.agent-runtime.persistence.database-enabled=true",
                        "datasmart.agent-runtime.tool-action-resume-facts.worker-receipt-index-store=memory",
                        "spring.flyway.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootCauseMessage(context.getStartupFailure()))
                            .contains("worker-receipt-index-store=postgresql");
                });
    }

    /**
     * 只打开数据库但关闭 Flyway 时必须失败，防止服务运行到第一条 callback SQL 才发现缺表。
     */
    @Test
    void shouldFailStartupWhenFlywayIsDisabled() {
        baseRunner
                .withPropertyValues(
                        "datasmart.agent-runtime.persistence.database-enabled=true",
                        "datasmart.agent-runtime.tool-action-resume-facts.worker-receipt-index-store=postgresql",
                        "spring.flyway.enabled=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootCauseMessage(context.getStartupFailure()))
                            .contains("spring.flyway.enabled=true");
                });
    }

    /**
     * 配置、V8/V9 迁移和三张表同时就绪时，最小上下文应成功创建校验器。
     */
    @Test
    void shouldStartWhenPostgresqlFlywayAndCallbackTablesAreReady() {
        new ApplicationContextRunner()
                .withUserConfiguration(ReadySchemaConfiguration.class)
                .withPropertyValues(
                        "datasmart.agent-runtime.async-task-final-state-callback-worker.enabled=true",
                        "datasmart.agent-runtime.persistence.database-enabled=true",
                        "datasmart.agent-runtime.tool-action-resume-facts.worker-receipt-index-store=postgresql",
                        "spring.flyway.enabled=true",
                        "spring.flyway.default-schema=agent_runtime")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(
                            AgentCommandTaskFinalStateCallbackWorkerPrerequisiteValidator.class);
                });
    }

    /**
     * 注册配置属性和被测校验器，不引入完整应用扫描。
     */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AgentRuntimePersistenceProperties.class)
    @Import(AgentCommandTaskFinalStateCallbackWorkerPrerequisiteValidator.class)
    static class ValidatorOnlyConfiguration {
    }

    /**
     * 为成功路径提供只返回迁移版本和表数量的模拟基础设施，不接触真实账号或业务数据。
     */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AgentRuntimePersistenceProperties.class)
    @Import(AgentCommandTaskFinalStateCallbackWorkerPrerequisiteValidator.class)
    static class ReadySchemaConfiguration {

        /** 返回已应用 V8/V9 的专用 Flyway 模拟对象。 */
        @Bean(name = "agentRuntimeFlyway")
        Flyway agentRuntimeFlyway() {
            Flyway flyway = mock(Flyway.class);
            MigrationInfoService infoService = mock(MigrationInfoService.class);
            MigrationInfo version8 = migration("8");
            MigrationInfo version9 = migration("9");
            when(flyway.info()).thenReturn(infoService);
            when(infoService.applied()).thenReturn(new MigrationInfo[]{version8, version9});
            return flyway;
        }

        /** 返回可见三张 callback 表的专用 DataSource 模拟对象。 */
        @Bean(name = "agentRuntimeJdbcDataSource")
        DataSource agentRuntimeJdbcDataSource() throws Exception {
            DataSource dataSource = mock(DataSource.class);
            Connection connection = mock(Connection.class);
            PreparedStatement statement = mock(PreparedStatement.class);
            ResultSet resultSet = mock(ResultSet.class);
            when(dataSource.getConnection()).thenReturn(connection);
            when(connection.prepareStatement(anyString())).thenReturn(statement);
            when(statement.executeQuery()).thenReturn(resultSet);
            when(resultSet.next()).thenReturn(true);
            when(resultSet.getInt(1)).thenReturn(3);
            return dataSource;
        }

        /**
         * 构造一个只携带版本号的迁移事实。
         */
        private MigrationInfo migration(String version) {
            MigrationInfo info = mock(MigrationInfo.class);
            when(info.getVersion()).thenReturn(MigrationVersion.fromVersion(version));
            return info;
        }
    }

    /**
     * Spring 上下文会用 BeanCreationException 包装业务异常；测试沿 cause 链读取最内层消息，
     * 这样断言关注真正的部署提示而不是 Spring 版本相关的外层文案。
     */
    private static String rootCauseMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current != null && current.getCause() != null) {
            current = current.getCause();
        }
        return current == null ? "" : String.valueOf(current.getMessage());
    }
}
