/**
 * @Author : Cui
 * @Date: 2026/06/20 03:42
 * @Description DataSmart Govern Backend - JdbcSyncBatchWriter.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.execution.jdbc;

import com.czh.datasmart.govern.datasource.service.execution.SyncBatchRecordBatch;
import com.czh.datasmart.govern.datasource.service.execution.SyncBatchWriteContext;
import com.czh.datasmart.govern.datasource.service.execution.SyncBatchWriteResult;
import com.czh.datasmart.govern.datasource.service.execution.SyncBatchWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

/**
 * JDBC 批处理写入器。
 *
 * <p>该实现负责把 reader 产出的内部记录批次写入目标端。
 * 它遵循几条生产安全原则：</p>
 * <p>1. 业务值全部通过 PreparedStatement 绑定，不拼接到 SQL；</p>
 * <p>2. 写入批次在一个事务内提交，失败时主动 rollback；</p>
 * <p>3. 返回数量摘要和低敏错误摘要，不返回失败行原文；</p>
 * <p>4. 真正的失败样本采集后续应进入单独 error sample 存储，并做脱敏与保留周期控制。</p>
 */
@Component
@RequiredArgsConstructor
public class JdbcSyncBatchWriter implements SyncBatchWriter {

    /**
     * JDBC 连接提供者。
     */
    private final SyncJdbcConnectionProvider syncJdbcConnectionProvider;

    @Override
    public SyncBatchWriteResult writeBatch(SyncBatchWriteContext context, SyncBatchRecordBatch recordBatch) {
        validateContext(context, recordBatch);
        if (recordBatch.isEmpty()) {
            return new SyncBatchWriteResult(0L, 0L, false, null);
        }
        Connection connection = null;
        try {
            connection = syncJdbcConnectionProvider.openConnection(context.getDatasourceId(), false);
            connection.setReadOnly(false);
            connection.setAutoCommit(false);
            try (PreparedStatement preparedStatement = connection.prepareStatement(context.getWriteStatement().getSql())) {
                for (Map<String, Object> row : recordBatch.getRows()) {
                    bindRow(preparedStatement, context.getWriteStatement().getParameterNames(), row);
                    preparedStatement.addBatch();
                }
                int[] updateCounts = preparedStatement.executeBatch();
                long recordsWritten = countSuccessfulUpdates(updateCounts);
                connection.commit();
                return new SyncBatchWriteResult(recordsWritten, recordBatch.size() - recordsWritten, true, null);
            }
        } catch (BatchUpdateException exception) {
            rollbackQuietly(connection);
            long successCount = countSuccessfulUpdates(exception.getUpdateCounts());
            return new SyncBatchWriteResult(successCount, recordBatch.size() - successCount, false, truncateMessage(exception.getMessage()));
        } catch (ClassNotFoundException | SQLException | RuntimeException exception) {
            rollbackQuietly(connection);
            return new SyncBatchWriteResult(0L, (long) recordBatch.size(), false, truncateMessage(exception.getMessage()));
        } finally {
            closeQuietly(connection);
        }
    }

    /**
     * 绑定一行记录。
     */
    private void bindRow(PreparedStatement preparedStatement, List<String> parameterNames, Map<String, Object> row) throws SQLException {
        if (parameterNames == null || parameterNames.isEmpty()) {
            throw new IllegalArgumentException("写入语句 parameterNames 不能为空");
        }
        for (int index = 0; index < parameterNames.size(); index++) {
            String parameterName = parameterNames.get(index);
            if (!row.containsKey(parameterName)) {
                throw new IllegalArgumentException("写入行缺少字段: " + parameterName);
            }
            preparedStatement.setObject(index + 1, row.get(parameterName));
        }
    }

    /**
     * 统计成功写入数量。
     */
    private long countSuccessfulUpdates(int[] updateCounts) {
        if (updateCounts == null) {
            return 0L;
        }
        long success = 0L;
        for (int updateCount : updateCounts) {
            if (updateCount >= 0 || updateCount == Statement.SUCCESS_NO_INFO) {
                success++;
            }
        }
        return success;
    }

    /**
     * 失败时静默回滚。
     *
     * <p>这里不把 rollback 异常继续抛出，是因为主失败原因更重要；
     * 后续接入日志和可观测性时，可以把 rollback 异常作为附加诊断指标记录。</p>
     */
    private void rollbackQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // 当前返回对象没有诊断扩展字段，先保持低敏失败摘要；后续可接入结构化日志。
        }
    }

    /**
     * 静默关闭连接。
     */
    private void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // 连接关闭失败不覆盖主执行结果。
        }
    }

    /**
     * 上下文校验。
     */
    private void validateContext(SyncBatchWriteContext context, SyncBatchRecordBatch recordBatch) {
        if (context == null || context.getWriteStatement() == null) {
            throw new IllegalArgumentException("write context 和 writeStatement 不能为空");
        }
        if (context.getDatasourceId() == null) {
            throw new IllegalArgumentException("写入数据源 ID 不能为空");
        }
        if (context.getWriteStatement().getSql() == null || context.getWriteStatement().getSql().isBlank()) {
            throw new IllegalArgumentException("写入 SQL 模板不能为空");
        }
        if (recordBatch == null || recordBatch.getRows() == null) {
            throw new IllegalArgumentException("recordBatch 不能为空");
        }
    }

    /**
     * 错误摘要截断。
     */
    private String truncateMessage(String message) {
        if (message == null || message.isBlank()) {
            return "未知 JDBC 写入错误";
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
