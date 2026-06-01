/**
 * @Author : Cui
 * @Date: 2026/06/01 23:02
 * @Description DataSmart Govern Backend - JdbcAgentAsyncTaskCommandOutboxCountSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.event.command;

import com.czh.datasmart.govern.agent.persistence.AgentRuntimeJdbcConnectionManager;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * JDBC 异步命令 outbox 计数查询支持类。
 *
 * <p>该类只承接 run/tenant backlog 计数，不负责 append、状态流转或 dispatcher 领取。
 * 之所以单独拆出来，是为了让 {@link JdbcAgentAsyncTaskCommandOutboxStore} 继续聚焦 outbox 状态机，
 * 避免一个 Store 文件同时承载写入、查询、诊断、恢复和容量计数而超过项目的单文件规模约束。</p>
 */
final class JdbcAgentAsyncTaskCommandOutboxCountSupport {

    private JdbcAgentAsyncTaskCommandOutboxCountSupport() {
        throw new UnsupportedOperationException("JdbcAgentAsyncTaskCommandOutboxCountSupport 是工具类，不允许实例化");
    }

    /**
     * 按某个 scope 字段统计指定状态集合的记录数。
     *
     * <p>当前 scope 字段只由 Store 内部传入固定值，例如 {@code run_id} 或 {@code tenant_id}，
     * 不接收外部请求参数，避免把列名拼接变成 SQL 注入入口。状态值仍通过 JDBC 参数绑定。</p>
     */
    static long countByScope(AgentRuntimeJdbcConnectionManager connectionManager,
                             String scopeColumn,
                             Object scopeValue,
                             Collection<AgentAsyncTaskCommandOutboxStatus> statuses,
                             String errorMessage) {
        CountQuery query = buildCountQuery(scopeColumn, scopeValue, statuses);
        try {
            return connectionManager.executeWithConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(query.sql())) {
                    JdbcAgentAsyncTaskCommandOutboxRecordMapper.bindParameters(statement, query.parameters());
                    try (ResultSet resultSet = statement.executeQuery()) {
                        return resultSet.next() ? resultSet.getLong("count_value") : 0L;
                    }
                }
            });
        } catch (RuntimeException exception) {
            throw new IllegalStateException(errorMessage, exception);
        }
    }

    private static CountQuery buildCountQuery(String scopeColumn,
                                              Object scopeValue,
                                              Collection<AgentAsyncTaskCommandOutboxStatus> statuses) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) AS count_value FROM agent_async_task_command_outbox WHERE ")
                .append(scopeColumn)
                .append(" = ?");
        List<Object> parameters = new ArrayList<>();
        parameters.add(scopeValue);
        if (statuses != null && !statuses.isEmpty()) {
            sql.append(" AND status IN (");
            int index = 0;
            for (AgentAsyncTaskCommandOutboxStatus status : statuses) {
                if (status == null) {
                    continue;
                }
                if (index++ > 0) {
                    sql.append(", ");
                }
                sql.append("?");
                parameters.add(status.name());
            }
            sql.append(")");
        }
        return new CountQuery(sql.toString(), parameters);
    }

    private record CountQuery(String sql, List<Object> parameters) {
    }
}
