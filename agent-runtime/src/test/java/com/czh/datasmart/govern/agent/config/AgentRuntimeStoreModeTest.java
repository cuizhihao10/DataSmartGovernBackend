/**
 * @Author : Cui
 * @Date: 2026/08/20 00:00
 * @Description DataSmart Govern Backend - AgentRuntimeStoreModeTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PostgreSQL 专用 Agent 仓储不能被历史 MySQL 兼容别名误装配。 */
class AgentRuntimeStoreModeTest {

    @Test
    void shouldSeparateGenericJdbcCompatibilityFromPostgresqlOnlyStores() {
        assertTrue(AgentRuntimeStoreMode.isJdbcDurable("mysql"));
        assertFalse(AgentRuntimeStoreMode.isPostgresqlDurable("mysql"));
        assertTrue(AgentRuntimeStoreMode.isPostgresqlDurable("postgresql"));
        assertTrue(AgentRuntimeStoreMode.isPostgresqlDurable("postgres"));
        assertTrue(AgentRuntimeStoreMode.isPostgresqlDurable("jdbc"));
        assertFalse(AgentRuntimeStoreMode.isPostgresqlDurable("memory"));
    }
}
