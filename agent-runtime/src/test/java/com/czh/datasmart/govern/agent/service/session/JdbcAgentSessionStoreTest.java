/**
 * @Author : Cui
 * @Date: 2026/08/05 00:00
 * @Description DataSmart Govern Backend - JdbcAgentSessionStoreTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.session;

import com.czh.datasmart.govern.agent.config.AgentRuntimePersistenceProperties;
import com.czh.datasmart.govern.agent.model.AgentRunState;
import com.czh.datasmart.govern.agent.model.WorkspaceIsolationLevel;
import com.czh.datasmart.govern.agent.persistence.AgentRuntimeJdbcConnectionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
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

    /**
     * Existing-session mutations must be based on a database-locked aggregate rather than on an earlier HTTP
     * snapshot. The parent lock prevents a concurrent child insert through the foreign key, while explicit child
     * row locks serialize confirmation receipts and lifecycle updates that address {@code agent_run} directly.
     * Only after every lock and the fresh aggregate read may the callback change state and persist the replacement.
     */
    @Test
    void mutateAtomicallyShouldLockTheWholeAggregateBeforePersistingChanges() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement lockSession = mock(PreparedStatement.class);
        PreparedStatement lockDelegation = mock(PreparedStatement.class);
        PreparedStatement lockBindings = mock(PreparedStatement.class);
        PreparedStatement lockRuns = mock(PreparedStatement.class);
        PreparedStatement lockMessages = mock(PreparedStatement.class);
        PreparedStatement selectSession = mock(PreparedStatement.class);
        PreparedStatement selectDelegation = mock(PreparedStatement.class);
        PreparedStatement selectBindings = mock(PreparedStatement.class);
        PreparedStatement selectRuns = mock(PreparedStatement.class);
        PreparedStatement selectMessages = mock(PreparedStatement.class);
        PreparedStatement writeStatement = mock(PreparedStatement.class);
        ResultSet lockedSession = mock(ResultSet.class);
        ResultSet parentSession = mock(ResultSet.class);
        ResultSet emptyChildren = mock(ResultSet.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(argThat(sql -> sql != null
                && sql.contains("SELECT session_id FROM agent_session") && sql.contains("FOR UPDATE"))))
                .thenReturn(lockSession);
        when(connection.prepareStatement(argThat(sql -> sql != null
                && sql.contains("SELECT delegation_id FROM agent_delegation") && sql.contains("FOR UPDATE"))))
                .thenReturn(lockDelegation);
        when(connection.prepareStatement(argThat(sql -> sql != null
                && sql.contains("SELECT binding_id FROM agent_session_tool_binding") && sql.contains("FOR UPDATE"))))
                .thenReturn(lockBindings);
        when(connection.prepareStatement(argThat(sql -> sql != null
                && sql.contains("SELECT run_id FROM agent_run") && sql.contains("FOR UPDATE"))))
                .thenReturn(lockRuns);
        when(connection.prepareStatement(argThat(sql -> sql != null
                && sql.contains("SELECT message_id FROM agent_conversation_message") && sql.contains("FOR UPDATE"))))
                .thenReturn(lockMessages);
        when(connection.prepareStatement("SELECT * FROM agent_session WHERE session_id = ?"))
                .thenReturn(selectSession);
        when(connection.prepareStatement("SELECT * FROM agent_delegation WHERE session_id = ?"))
                .thenReturn(selectDelegation);
        when(connection.prepareStatement(argThat(sql -> sql != null
                && sql.startsWith("SELECT * FROM agent_session_tool_binding"))))
                .thenReturn(selectBindings);
        when(connection.prepareStatement(argThat(sql -> sql != null
                && sql.startsWith("SELECT * FROM agent_run"))))
                .thenReturn(selectRuns);
        when(connection.prepareStatement(argThat(sql -> sql != null
                && sql.startsWith("SELECT * FROM agent_conversation_message"))))
                .thenReturn(selectMessages);
        when(connection.prepareStatement(argThat(sql -> sql != null
                && (sql.startsWith("INSERT INTO") || sql.startsWith("DELETE FROM") || sql.startsWith("UPDATE ")))))
                .thenReturn(writeStatement);

        when(lockSession.executeQuery()).thenReturn(lockedSession);
        when(lockedSession.next()).thenReturn(true);
        when(lockDelegation.executeQuery()).thenReturn(emptyChildren);
        when(lockBindings.executeQuery()).thenReturn(emptyChildren);
        when(lockRuns.executeQuery()).thenReturn(emptyChildren);
        when(lockMessages.executeQuery()).thenReturn(emptyChildren);
        when(selectSession.executeQuery()).thenReturn(parentSession);
        when(parentSession.next()).thenReturn(true);
        when(parentSession.wasNull()).thenReturn(true);
        when(parentSession.getString("agent_id")).thenReturn("datasmart-govern-agent");
        when(parentSession.getString("actor_id")).thenReturn("u-001");
        when(parentSession.getString("channel")).thenReturn("WEB");
        when(parentSession.getString("objective")).thenReturn("Run a governed synchronization task");
        when(parentSession.getString("isolation_level")).thenReturn("PROJECT");
        when(parentSession.getString("workspace_key")).thenReturn("tenant:10:project:20");
        when(parentSession.getString("state")).thenReturn("ACTIVE");
        when(selectDelegation.executeQuery()).thenReturn(emptyChildren);
        when(selectBindings.executeQuery()).thenReturn(emptyChildren);
        when(selectRuns.executeQuery()).thenReturn(emptyChildren);
        when(selectMessages.executeQuery()).thenReturn(emptyChildren);

        JdbcAgentSessionStore store = store(dataSource);

        Optional<Boolean> changed = store.mutateAtomically("session-locked", session -> {
            session.setPinned(true);
            return session.isPinned();
        });

        assertEquals(Optional.of(true), changed);
        verify(lockSession).setString(1, "session-locked");
        verify(lockDelegation).setString(1, "session-locked");
        verify(lockBindings).setString(1, "session-locked");
        verify(lockRuns).setString(1, "session-locked");
        verify(lockMessages).setString(1, "session-locked");
        verify(connection).commit();
    }

    /**
     * Confirmation refreshes the Gateway-authenticated delegated identity before any tool side effect.
     * That refresh must touch only the parent session row: replacing child collections from the session snapshot
     * would erase a Run or message concurrently appended by Python continuation or another Runtime instance.
     */
    @Test
    void refreshDelegatedIdentityShouldUseOneNarrowParentUpdate() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement updateSession = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(argThat(sql -> sql != null
                && sql.contains("UPDATE agent_session")
                && sql.contains("authorized_project_roles"))))
                .thenReturn(updateSession);
        when(updateSession.executeUpdate()).thenReturn(1);
        JdbcAgentSessionStore store = store(dataSource);

        boolean refreshed = store.refreshDelegatedIdentity(
                "session-identity", "PROJECT_MANAGER", "USER", "101:MANAGER");

        assertTrue(refreshed);
        verify(updateSession).setString(1, "PROJECT_MANAGER");
        verify(updateSession).setString(2, "USER");
        verify(updateSession).setString(3, "101:MANAGER");
        verify(updateSession).setString(6, "session-identity");
        verify(connection, never()).prepareStatement(argThat(sql -> sql != null && sql.contains("DELETE FROM")));
        verify(connection).commit();
    }

    /**
     * A completed tool batch persists only the source Run lifecycle and the parent activity timestamp.
     * The SQL is deliberately conditional: a stale request cannot replace a newer lifecycle snapshot or change a
     * different terminal result, and it never deletes sibling Runs created by the post-confirm continuation.
     */
    @Test
    void updateRunLifecycleShouldPreserveSiblingChildrenAndRejectStaleTerminalOverwrite() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement lockSession = mock(PreparedStatement.class);
        PreparedStatement updateRun = mock(PreparedStatement.class);
        PreparedStatement updateSession = mock(PreparedStatement.class);
        ResultSet lockedSession = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(argThat(sql -> sql != null
                && sql.contains("SELECT session_id FROM agent_session") && sql.contains("FOR UPDATE"))))
                .thenReturn(lockSession);
        when(connection.prepareStatement(argThat(sql -> sql != null
                && sql.contains("UPDATE agent_run")
                && sql.contains("state NOT IN"))))
                .thenReturn(updateRun);
        when(connection.prepareStatement(argThat(sql -> sql != null
                && sql.contains("UPDATE agent_session")
                && !sql.contains("authorized_project_roles"))))
                .thenReturn(updateSession);
        when(lockSession.executeQuery()).thenReturn(lockedSession);
        when(lockedSession.next()).thenReturn(true);
        when(updateRun.executeUpdate()).thenReturn(1);
        when(updateSession.executeUpdate()).thenReturn(1);
        JdbcAgentSessionStore store = store(dataSource);
        AgentRunRecord terminalRun = new AgentRunRecord(
                "run-terminal",
                "session-terminal",
                AgentRunState.PLANNING,
                "AGENT_REASONING",
                "Execute governed tools",
                false,
                true,
                List.of(),
                Map.of("confirmedExecutionClaim", Map.of("state", "IN_PROGRESS")),
                LocalDateTime.of(2026, 8, 12, 1, 0),
                "Tools are executing"
        );
        terminalRun.completeAfterToolExecution("All governed tools completed successfully.");

        boolean updated = store.updateRunLifecycle("session-terminal", terminalRun);

        assertTrue(updated);
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(connection, atLeastOnce()).prepareStatement(sqlCaptor.capture());
        String lifecycleSql = sqlCaptor.getAllValues().stream()
                .filter(sql -> sql.contains("UPDATE agent_run"))
                .findFirst()
                .orElseThrow();
        assertTrue(lifecycleSql.contains("update_time <= ?"));
        assertTrue(lifecycleSql.contains("state = ?"));
        assertTrue(lifecycleSql.contains("state NOT IN ('SUCCEEDED', 'REJECTED', 'FAILED', 'CANCELLED')"));
        assertFalse(lifecycleSql.contains("variables ="),
                "Run lifecycle persistence must not replace immutable confirmation or authorization variables");
        verify(updateRun).setString(1, "SUCCEEDED");
        verify(updateRun).setString(eq(2), org.mockito.ArgumentMatchers.argThat(json ->
                json.contains("Agent control-plane plan") || json.contains("Agent 控制面计划")));
        verify(updateRun).setString(6, "session-terminal");
        verify(updateRun).setString(7, "run-terminal");
        verify(updateRun).setString(9, "SUCCEEDED");
        verify(connection, never()).prepareStatement(argThat(sql -> sql != null && (
                sql.contains("DELETE FROM agent_run")
                        || sql.contains("DELETE FROM agent_tool_binding")
                        || sql.contains("DELETE FROM agent_conversation_message")
        )));
        verify(connection).commit();
    }

    /**
     * Approval reconciliation may keep a Run waiting, resume it to planning, or close it as rejected. The durable
     * update must accept only the original WAITING_HUMAN state (plus an idempotent repeat of the target) so a late
     * approval callback cannot roll a newer model/tool state backwards or replace sibling session children.
     */
    @Test
    void updateRunAfterToolDecisionShouldRequireWaitingHumanOrSameTargetState() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement lockSession = mock(PreparedStatement.class);
        PreparedStatement updateRun = mock(PreparedStatement.class);
        PreparedStatement updateSession = mock(PreparedStatement.class);
        ResultSet lockedSession = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(argThat(sql -> sql != null
                && sql.contains("SELECT session_id FROM agent_session") && sql.contains("FOR UPDATE"))))
                .thenReturn(lockSession);
        when(connection.prepareStatement(argThat(sql -> sql != null
                && sql.contains("UPDATE agent_run")
                && sql.contains("state = 'WAITING_HUMAN'"))))
                .thenReturn(updateRun);
        when(connection.prepareStatement(argThat(sql -> sql != null && sql.contains("UPDATE agent_session"))))
                .thenReturn(updateSession);
        when(lockSession.executeQuery()).thenReturn(lockedSession);
        when(lockedSession.next()).thenReturn(true);
        when(updateRun.executeUpdate()).thenReturn(1);
        when(updateSession.executeUpdate()).thenReturn(1);
        JdbcAgentSessionStore store = store(dataSource);
        AgentRunRecord approvedRun = new AgentRunRecord(
                "run-approved",
                "session-approved",
                AgentRunState.WAITING_HUMAN,
                "AGENT_REASONING",
                "Approve governed tools",
                false,
                true,
                List.of(),
                Map.of(),
                LocalDateTime.of(2026, 8, 12, 1, 30),
                "Waiting for approval"
        );
        approvedRun.resumePlanningAfterApproval(List.of("Continue governed execution"), "Approval completed.");

        boolean updated = store.updateRunAfterToolDecision("session-approved", approvedRun);

        assertTrue(updated);
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(connection, atLeastOnce()).prepareStatement(sqlCaptor.capture());
        String decisionSql = sqlCaptor.getAllValues().stream()
                .filter(sql -> sql.contains("UPDATE agent_run"))
                .findFirst()
                .orElseThrow();
        assertTrue(decisionSql.contains("update_time <= ?"));
        assertTrue(decisionSql.contains("state = 'WAITING_HUMAN' OR state = ?"));
        assertFalse(decisionSql.contains("variables ="));
        verify(updateRun).setString(1, "PLANNING");
        verify(updateRun).setString(9, "PLANNING");
        verify(connection, never()).prepareStatement(argThat(sql -> sql != null && sql.contains("DELETE FROM")));
        verify(connection).commit();
    }

    @Test
    void sessionUpsertShouldUseOnePlaceholderPerApplicationScopedColumn() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        JdbcAgentSessionStore store = store(dataSource);
        AgentSessionRecord session = new AgentSessionRecord(
                "session-application-scope",
                10L,
                101L,
                null,
                "1001",
                "PROJECT_OWNER",
                "USER",
                "101:OWNER",
                "LOCAL_E2E",
                "验证应用范围会话持久化。",
                WorkspaceIsolationLevel.PROJECT,
                "tenant:10:project:101",
                LocalDateTime.of(2026, 8, 10, 0, 30)
        );
        session.bindApplicationId(10010L);

        store.save(session);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(connection, atLeastOnce()).prepareStatement(sqlCaptor.capture());
        String sessionUpsert = sqlCaptor.getAllValues().stream()
                .filter(sql -> sql.contains("INSERT INTO agent_session ("))
                .findFirst()
                .orElseThrow();
        long placeholderCount = sessionUpsert.chars().filter(character -> character == '?').count();
        assertEquals(20L, placeholderCount,
                "agent_session 的 20 个列值必须与 20 个 JDBC 绑定参数一一对应");
        verify(statement).setLong(4, 10010L);
        verify(connection).commit();
    }

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
     * 确认 claim 与 Autopilot 授权必须通过同一条条件 UPDATE 原子写入。
     *
     * <p>测试刻意不执行 PostgreSQL，而是观察 Store 交给驱动的 SQL 和参数：每个待写键都要有 JSONB
     * 冲突条件，完整 Map 只能作为一个 JSON 参数绑定，且不能出现删除 Run/消息的整聚合替换。这样可以直接
     * 防止“先 SELECT、再 UPDATE”的并发穿透，以及为了保存 receipt 意外删除 continuation Run 的回归。</p>
     */
    @Test
    void putRunVariablesIfAbsentShouldUseOneAtomicJsonbUpdate() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement updateRun = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(argThat(sql -> sql != null && sql.contains("UPDATE agent_run"))))
                .thenReturn(updateRun);
        when(updateRun.executeUpdate()).thenReturn(1);
        JdbcAgentSessionStore store = store(dataSource);
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("confirmedExecutionClaim", Map.of("state", "IN_PROGRESS"));
        variables.put("autopilotAuthorization", Map.of("executionMode", "AUTOPILOT"));

        boolean inserted = store.putRunVariablesIfAbsent(
                "session-confirmation", "run-confirmation", "confirmedExecutionClaim", variables);

        assertTrue(inserted);
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sqlCaptor.capture());
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("variables = COALESCE(variables, '{}'::jsonb) || CAST(? AS jsonb)"));
        assertEquals(2, sql.split("AND NOT jsonb_exists\\(COALESCE\\(variables", -1).length - 1,
                "claim 与授权两个键都必须参与冲突条件");
        assertEquals(7L, sql.chars().filter(character -> character == '?').count(),
                "JSONB 键存在性判断不能使用 JDBC 会误认成占位符的 PostgreSQL ? 运算符");
        verify(updateRun).setString(eq(1), org.mockito.ArgumentMatchers.argThat(json ->
                json.contains("confirmedExecutionClaim") && json.contains("autopilotAuthorization")));
        verify(updateRun).setString(4, "session-confirmation");
        verify(updateRun).setString(5, "run-confirmation");
        verify(updateRun).setString(6, "confirmedExecutionClaim");
        verify(updateRun).setString(7, "autopilotAuthorization");
        verify(connection, never()).prepareStatement(argThat(candidate -> candidate != null
                && candidate.contains("DELETE FROM agent_run")));
        verify(connection).commit();
    }

    /**
     * Application binding must update only the session parent row and preserve every child collection.
     *
     * <p>This test guards the regression where confirmation called {@code save(session)} immediately after its
     * atomic claim. The aggregate save deletes and reinserts Runs/messages, so an older snapshot could erase a
     * concurrently appended continuation Run. The narrow SQL is also fail-closed for a different existing scope.</p>
     */
    @Test
    void bindApplicationIdShouldUseNarrowAbsentOrEqualUpdate() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement updateSession = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(argThat(sql -> sql != null
                && sql.contains("UPDATE agent_session")
                && sql.contains("application_id IS NULL OR application_id = ?"))))
                .thenReturn(updateSession);
        when(updateSession.executeUpdate()).thenReturn(1);
        JdbcAgentSessionStore store = store(dataSource);

        boolean bound = store.bindApplicationIdIfAbsent("session-application", 201L);

        assertTrue(bound);
        verify(updateSession).setLong(1, 201L);
        verify(updateSession).setString(4, "session-application");
        verify(updateSession).setLong(5, 201L);
        verify(connection).commit();
    }

    /**
     * Verifies that the durable AUTOPILOT claim serializes all Runs through the session parent row.
     *
     * <p>The old Run-local update could safely reject duplicate confirmation of one Run but let two different
     * Runs independently add {@code autopilotAuthorization}. The new method must first lock the parent session,
     * then scan the complete child Run set, and only then issue the original conditional target-Run update. The
     * test is intentionally SQL-contract focused: PostgreSQL transaction behavior is supplied by the database,
     * while this test prevents a future refactor from quietly deleting the only cross-instance lock boundary.</p>
     */
    @Test
    void firstAutopilotAuthorizationShouldLockSessionBeforeCheckingAllRuns() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement lockSession = mock(PreparedStatement.class);
        PreparedStatement findAuthorization = mock(PreparedStatement.class);
        PreparedStatement updateRun = mock(PreparedStatement.class);
        ResultSet lockedSession = mock(ResultSet.class);
        ResultSet noAuthorization = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(argThat(sql -> sql != null && sql.contains("FROM agent_session")
                && sql.contains("FOR UPDATE")))).thenReturn(lockSession);
        when(connection.prepareStatement(argThat(sql -> sql != null && sql.contains("FROM agent_run")
                && sql.contains("LIMIT 1")))).thenReturn(findAuthorization);
        when(connection.prepareStatement(argThat(sql -> sql != null && sql.contains("UPDATE agent_run"))))
                .thenReturn(updateRun);
        when(lockSession.executeQuery()).thenReturn(lockedSession);
        when(lockedSession.next()).thenReturn(true);
        when(findAuthorization.executeQuery()).thenReturn(noAuthorization);
        when(noAuthorization.next()).thenReturn(false);
        when(updateRun.executeUpdate()).thenReturn(1);
        JdbcAgentSessionStore store = store(dataSource);

        boolean inserted = store.putRunVariablesIfAbsentAndSessionVariableAbsent(
                "session-autopilot", "run-autopilot", "confirmedExecutionClaim", "autopilotAuthorization",
                Map.of(
                        "confirmedExecutionClaim", Map.of("state", "IN_PROGRESS"),
                        "autopilotAuthorization", Map.of("executionMode", "AUTOPILOT")
                ));

        assertTrue(inserted);
        verify(lockSession).setString(1, "session-autopilot");
        verify(findAuthorization).setString(1, "session-autopilot");
        verify(findAuthorization).setString(2, "autopilotAuthorization");
        verify(updateRun).setString(4, "session-autopilot");
        verify(updateRun).setString(5, "run-autopilot");
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(connection, atLeastOnce()).prepareStatement(sqlCaptor.capture());
        assertTrue(sqlCaptor.getAllValues().stream().anyMatch(sql -> sql.contains("FOR UPDATE")));
        assertTrue(sqlCaptor.getAllValues().stream().anyMatch(sql -> sql.contains("FROM agent_run")
                && sql.contains("jsonb_exists") && sql.contains("LIMIT 1")));
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
