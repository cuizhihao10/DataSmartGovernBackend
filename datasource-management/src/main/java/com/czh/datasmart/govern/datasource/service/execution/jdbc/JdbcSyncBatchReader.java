/**
 * @Author : Cui
 * @Date: 2026/06/20 03:42
 * @Description DataSmart Govern Backend - JdbcSyncBatchReader.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.execution.jdbc;

import com.czh.datasmart.govern.datasource.service.execution.SyncBatchReadContext;
import com.czh.datasmart.govern.datasource.service.execution.SyncBatchReadResult;
import com.czh.datasmart.govern.datasource.service.execution.SyncBatchReader;
import com.czh.datasmart.govern.datasource.service.execution.SyncBatchRecordBatch;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JDBC 批处理读取器。
 *
 * <p>这是 datasource-management 数据同步链路第一次真正具备“执行 PreparedStatement 读取一批数据”的能力。
 * 该实现仍保持受控边界：</p>
 * <p>1. SQL 模板来自前置方言层，不在这里拼接对象名或业务条件；</p>
 * <p>2. checkpointValue、limit 等变量全部通过 PreparedStatement 参数绑定；</p>
 * <p>3. 返回的行数据只封装进 `SyncBatchRecordBatch`，供 writer 内部使用，不能直接进入 API 或日志；</p>
 * <p>4. 每次只读取一个批次，避免大表一次性加载到内存。</p>
 */
@Component
@RequiredArgsConstructor
public class JdbcSyncBatchReader implements SyncBatchReader {

    /**
     * JDBC 连接提供者。
     */
    private final SyncJdbcConnectionProvider syncJdbcConnectionProvider;

    @Override
    public SyncBatchReadResult readNextBatch(SyncBatchReadContext context) {
        validateContext(context);
        long recordsRead = 0;
        try (Connection connection = syncJdbcConnectionProvider.openConnection(context.getDatasourceId(), true);
             PreparedStatement preparedStatement = connection.prepareStatement(context.getReadStatement().getSql())) {
            connection.setReadOnly(true);
            preparedStatement.setFetchSize(safeFetchSize(context.getFetchSize()));
            bindParameters(preparedStatement, context);

            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                ResultSetMetaData metaData = resultSet.getMetaData();
                List<String> columns = resolveColumns(metaData);
                List<Map<String, Object>> rows = new ArrayList<>();
                while (resultSet.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int columnIndex = 1; columnIndex <= columns.size(); columnIndex++) {
                        row.put(columns.get(columnIndex - 1), resultSet.getObject(columnIndex));
                    }
                    rows.add(row);
                }
                recordsRead = rows.size();
                boolean endOfSource = recordsRead < safeFetchSize(context.getFetchSize());
                return new SyncBatchReadResult(
                        recordsRead,
                        endOfSource,
                        recordsRead > 0,
                        null,
                        new SyncBatchRecordBatch(columns, rows)
                );
            }
        } catch (ClassNotFoundException | SQLException | RuntimeException exception) {
            return new SyncBatchReadResult(
                    recordsRead,
                    false,
                    false,
                    truncateMessage(exception.getMessage()),
                    new SyncBatchRecordBatch(List.of(), List.of())
            );
        }
    }

    /**
     * 按方言生成的参数顺序绑定 PreparedStatement。
     */
    private void bindParameters(PreparedStatement preparedStatement, SyncBatchReadContext context) throws SQLException {
        List<String> parameterNames = context.getReadStatement().getParameterNames();
        if (parameterNames == null) {
            return;
        }
        for (int index = 0; index < parameterNames.size(); index++) {
            String parameterName = parameterNames.get(index);
            Object value = resolveParameterValue(context, parameterName);
            preparedStatement.setObject(index + 1, value);
        }
    }

    /**
     * 解析参数值。
     */
    private Object resolveParameterValue(SyncBatchReadContext context, String parameterName) {
        if ("limit".equals(parameterName)) {
            return safeFetchSize(context.getFetchSize());
        }
        if (context.getParameterValues() == null || !context.getParameterValues().containsKey(parameterName)) {
            throw new IllegalArgumentException("读取参数缺失: " + parameterName);
        }
        return context.getParameterValues().get(parameterName);
    }

    /**
     * 从 ResultSet 元数据提取字段名。
     */
    private List<String> resolveColumns(ResultSetMetaData metaData) throws SQLException {
        List<String> columns = new ArrayList<>();
        for (int columnIndex = 1; columnIndex <= metaData.getColumnCount(); columnIndex++) {
            columns.add(metaData.getColumnLabel(columnIndex));
        }
        return columns;
    }

    /**
     * 上下文校验。
     */
    private void validateContext(SyncBatchReadContext context) {
        if (context == null || context.getReadStatement() == null) {
            throw new IllegalArgumentException("read context 和 readStatement 不能为空");
        }
        if (context.getDatasourceId() == null) {
            throw new IllegalArgumentException("读取数据源 ID 不能为空");
        }
        if (context.getReadStatement().getSql() == null || context.getReadStatement().getSql().isBlank()) {
            throw new IllegalArgumentException("读取 SQL 模板不能为空");
        }
    }

    /**
     * fetchSize 兜底。
     */
    private int safeFetchSize(Integer fetchSize) {
        return fetchSize == null || fetchSize <= 0 ? 1000 : fetchSize;
    }

    /**
     * 错误摘要截断，避免底层驱动返回过长消息。
     */
    private String truncateMessage(String message) {
        if (message == null || message.isBlank()) {
            return "未知 JDBC 读取错误";
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
