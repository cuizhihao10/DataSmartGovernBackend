/**
 * @Author : Cui
 * @Date: 2026/08/05 00:00
 * @Description DataSmart Govern Backend - JdbcAgentSessionStoreTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.session;

import com.czh.datasmart.govern.agent.config.AgentRuntimePersistenceProperties;
import com.czh.datasmart.govern.agent.persistence.AgentRuntimeJdbcConnectionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JDBC 会话存储的增量消息契约测试。
 *
 * <p>本测试不连接真实 PostgreSQL，而是精确检查 append 路径使用的 SQL 边界。真实表结构、外键和 PostgreSQL
 * 方言由 Flyway migration 负责；这里重点防止维护者未来为了“复用代码”把消息追加重新改回 {@code save(session)}
 * 或 {@code replaceRuns()}，从而再次删除 continuation 刚写入的 Run。</p>
 */
class JdbcAgentSessionStoreTest {

    @Test
    void appendConversationMessageShouldNeverReplaceRunsOrOtherChildren() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement updateSession = mock(PreparedStatement.class);
        PreparedStatement insertMessage = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(argThat(sql -> sql != null && sql.contains("UPDATE agent_session"))))
                .thenReturn(updateSession);
        when(connection.prepareStatement(argThat(sql -> sql != null
                && sql.contains("INSERT INTO agent_conversation_message"))))
                .thenReturn(insertMessage);
        when(updateSession.executeUpdate()).thenReturn(1);
        when(insertMessage.executeUpdate()).thenReturn(1);
        JdbcAgentSessionStore store = store(dataSource);

        boolean appended = store.appendConversationMessage(
                "session-atomic-append",
                new AgentConversationMessageRecord(
                        "message-atomic-append",
                        "run-source",
                        "AGENT",
                        "已生成新的受控修复计划。",
                        LocalDateTime.of(2026, 8, 5, 4, 30)
                )
        );

        assertTrue(appended);
        verify(updateSession).setString(5, "session-atomic-append");
        verify(insertMessage).setString(1, "message-atomic-append");
        verify(insertMessage).setString(2, "session-atomic-append");
        verify(insertMessage).setString(3, "run-source");
        verify(connection, never()).prepareStatement(argThat(sql -> sql != null && (
                sql.contains("DELETE FROM agent_run")
                        || sql.contains("DELETE FROM agent_tool_binding")
                        || sql.contains("DELETE FROM agent_conversation_message")
        )));
        verify(connection).commit();
    }

    @Test
    void appendConversationMessageShouldStopWhenParentSessionNoLongerExists() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement updateSession = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(argThat(sql -> sql != null && sql.contains("UPDATE agent_session"))))
                .thenReturn(updateSession);
        when(updateSession.executeUpdate()).thenReturn(0);
        JdbcAgentSessionStore store = store(dataSource);

        boolean appended = store.appendConversationMessage(
                "missing-session",
                new AgentConversationMessageRecord(
                        "message-without-session",
                        "run-source",
                        "AGENT",
                        "这条消息不应成为孤儿记录。",
                        LocalDateTime.of(2026, 8, 5, 4, 31)
                )
        );

        assertFalse(appended);
        verify(connection, never()).prepareStatement(argThat(sql -> sql != null
                && sql.contains("INSERT INTO agent_conversation_message")));
        verify(connection).commit();
    }

    /**
     * 构造启用 JDBC 的最小 Store。连接管理器仍负责真实的 commit/rollback/close 调用，测试因此也能验证
     * “更新时间检查 + 消息插入”确实共享同一事务，而不是两次独立自动提交。
     */
    private JdbcAgentSessionStore store(DataSource dataSource) {
        AgentRuntimePersistenceProperties properties = new AgentRuntimePersistenceProperties();
        properties.setDatabaseEnabled(true);
        properties.getJdbc().setMaxQueryLimit(100);
        AgentRuntimeJdbcConnectionManager connectionManager =
                new AgentRuntimeJdbcConnectionManager(dataSource, properties);
        return new JdbcAgentSessionStore(connectionManager, new ObjectMapper(), properties);
    }
}
