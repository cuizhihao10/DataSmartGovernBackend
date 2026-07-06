/**
 * @Author : Cui
 * @Date: 2026/06/20 03:42
 * @Description DataSmart Govern Backend - JdbcSyncBatchReaderWriterTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.execution.jdbc;

import com.czh.datasmart.govern.datasource.service.execution.SyncBatchReadContext;
import com.czh.datasmart.govern.datasource.service.execution.SyncBatchReadResult;
import com.czh.datasmart.govern.datasource.service.execution.SyncBatchRecordBatch;
import com.czh.datasmart.govern.datasource.service.execution.SyncBatchWriteContext;
import com.czh.datasmart.govern.datasource.service.execution.SyncBatchWriteResult;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JDBC 批处理 reader/writer 测试。
 *
 * <p>这里不连接真实数据库，而是用 JDK 动态代理模拟 JDBC 接口。
 * 测试重点不是数据库语法是否可执行，而是 worker 执行层是否正确遵守关键原则：</p>
 * <p>1. reader 按 `parameterNames` 绑定 checkpoint 和 limit；</p>
 * <p>2. reader 只读取一个批次，并把行数据放入内部 `SyncBatchRecordBatch`；</p>
 * <p>3. writer 按字段顺序绑定每一行并调用 batch；</p>
 * <p>4. writer 成功时 commit，失败时 rollback，结果不返回失败行原文。</p>
 */
class JdbcSyncBatchReaderWriterTest {

    @Test
    void readerShouldBindParametersAndReturnInternalRecordBatch() {
        RecordingJdbc jdbc = new RecordingJdbc(List.of(
                row("id", 1L, "updated_at", "2026-06-20T03:00:00", "amount", 19.8),
                row("id", 2L, "updated_at", "2026-06-20T03:01:00", "amount", 20.5)
        ), new int[0], false);
        JdbcSyncBatchReader reader = new JdbcSyncBatchReader(jdbc);

        SyncBatchReadResult result = reader.readNextBatch(new SyncBatchReadContext(
                100L,
                900L,
                10L,
                "TIME_WATERMARK",
                2,
                new SyncPreparedJdbcStatement(
                        "INCREMENTAL_READ",
                        "SELECT id, updated_at, amount FROM ods.orders WHERE updated_at > ? LIMIT ?",
                        List.of("checkpointValue", "limit"),
                        List.of("SQL_TEMPLATE_INTERNAL_ONLY")
                ),
                Map.of("checkpointValue", "2026-06-20T02:00:00")
        ));

        assertEquals(2L, result.getRecordsRead());
        assertFalse(result.getEndOfSource());
        assertTrue(result.getCheckpointRecommended());
        assertEquals(List.of("id", "updated_at", "amount"), result.getRecordBatch().getColumns());
        assertEquals(2, result.getRecordBatch().size());
        assertEquals("2026-06-20T02:00:00", jdbc.boundParameters.get(1));
        assertEquals(2, jdbc.boundParameters.get(2));
        assertEquals(2, jdbc.fetchSize);
    }

    @Test
    void readerShouldReturnFailureSummaryWhenRequiredParameterMissing() {
        RecordingJdbc jdbc = new RecordingJdbc(List.of(), new int[0], false);
        JdbcSyncBatchReader reader = new JdbcSyncBatchReader(jdbc);

        SyncBatchReadResult result = reader.readNextBatch(new SyncBatchReadContext(
                100L,
                900L,
                10L,
                "TIME_WATERMARK",
                2,
                new SyncPreparedJdbcStatement(
                        "INCREMENTAL_READ",
                        "SELECT id FROM ods.orders WHERE id > ? LIMIT ?",
                        List.of("checkpointValue", "limit"),
                        List.of("SQL_TEMPLATE_INTERNAL_ONLY")
                ),
                Map.of()
        ));

        assertEquals(0L, result.getRecordsRead());
        assertFalse(result.getCheckpointRecommended());
        assertTrue(result.getErrorSummary().contains("读取参数缺失"));
        assertTrue(result.getRecordBatch().isEmpty());
    }

    @Test
    void writerShouldBindRowsAndCommitTransaction() {
        RecordingJdbc jdbc = new RecordingJdbc(List.of(), new int[]{1, 1}, false);
        JdbcSyncBatchWriter writer = new JdbcSyncBatchWriter(jdbc);

        SyncBatchWriteResult result = writer.writeBatch(writeContext(), new SyncBatchRecordBatch(
                List.of("id", "amount"),
                List.of(row("id", 1L, "amount", 19.8), row("id", 2L, "amount", 20.5))
        ));

        assertEquals(2L, result.getRecordsWritten());
        assertEquals(0L, result.getFailedRecordCount());
        assertTrue(result.getCommitRecommended());
        assertTrue(jdbc.committed);
        assertFalse(jdbc.rolledBack);
        assertEquals(2, jdbc.addBatchCount);
        assertEquals(List.of(Map.of(1, 1L, 2, 19.8), Map.of(1, 2L, 2, 20.5)), jdbc.batchParameterSnapshots);
    }

    @Test
    void writerShouldIsolateDirtyRowWhenRowMissesRequiredColumn() {
        RecordingJdbc jdbc = new RecordingJdbc(List.of(), new int[]{1}, false);
        JdbcSyncBatchWriter writer = new JdbcSyncBatchWriter(jdbc);

        SyncBatchWriteResult result = writer.writeBatch(writeContext(), new SyncBatchRecordBatch(
                List.of("id", "amount"),
                List.of(row("id", 1L))
        ));

        assertEquals(0L, result.getRecordsWritten());
        assertEquals(1L, result.getFailedRecordCount());
        assertTrue(result.getCommitRecommended());
        assertTrue(jdbc.committed);
        assertTrue(jdbc.rolledBack);
        assertTrue(result.getErrorSummary().contains("行级隔离"));
        assertEquals(1, result.getDirtySamples().size());
        assertEquals("FIELD_MAPPING_ERROR", result.getDirtySamples().get(0).getErrorType());
        assertTrue(result.getDirtySamples().get(0).getSourceRecordKey().contains("PRIMARY_KEY_EQ"));
        assertTrue(result.getDirtySamples().get(0).getSamplePayload().contains("sourceRecordKey"));
    }

    private SyncBatchWriteContext writeContext() {
        return new SyncBatchWriteContext(
                100L,
                900L,
                20L,
                "APPEND",
                100,
                100,
                List.of("id"),
                new SyncPreparedJdbcStatement(
                        "APPEND_WRITE",
                        "INSERT INTO dwd.orders (id, amount) VALUES (?, ?)",
                        List.of("id", "amount"),
                        List.of("SQL_TEMPLATE_INTERNAL_ONLY")
                )
        );
    }

    private Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            row.put(String.valueOf(pairs[index]), pairs[index + 1]);
        }
        return row;
    }

    /**
     * 记录型 JDBC 替身。
     *
     * <p>它只实现本测试会用到的 JDBC 方法，其他方法返回默认值。
     * 这样可以验证参数绑定和事务行为，而不依赖真实数据库或第三方 mock 框架。</p>
     */
    private static class RecordingJdbc implements SyncJdbcConnectionProvider {
        private final List<Map<String, Object>> rows;
        private final int[] updateCounts;
        private final boolean failOnExecuteBatch;
        private final Map<Integer, Object> boundParameters = new LinkedHashMap<>();
        private final List<Map<Integer, Object>> batchParameterSnapshots = new ArrayList<>();
        private int fetchSize;
        private int addBatchCount;
        private boolean committed;
        private boolean rolledBack;

        private RecordingJdbc(List<Map<String, Object>> rows, int[] updateCounts, boolean failOnExecuteBatch) {
            this.rows = rows;
            this.updateCounts = updateCounts;
            this.failOnExecuteBatch = failOnExecuteBatch;
        }

        @Override
        public Connection openConnection(Long datasourceId, boolean readOnly) {
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "prepareStatement" -> preparedStatement();
                case "commit" -> {
                    committed = true;
                    yield null;
                }
                case "rollback" -> {
                    rolledBack = true;
                    yield null;
                }
                default -> defaultValue(method);
            };
            return proxy(Connection.class, handler);
        }

        private PreparedStatement preparedStatement() {
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "setObject" -> {
                    boundParameters.put((Integer) args[0], args[1]);
                    yield null;
                }
                case "setFetchSize" -> {
                    fetchSize = (Integer) args[0];
                    yield null;
                }
                case "addBatch" -> {
                    addBatchCount++;
                    batchParameterSnapshots.add(new LinkedHashMap<>(boundParameters));
                    boundParameters.clear();
                    yield null;
                }
                case "executeQuery" -> resultSet();
                case "executeBatch" -> {
                    if (failOnExecuteBatch) {
                        throw new SQLException("模拟批处理失败");
                    }
                    yield updateCounts;
                }
                default -> defaultValue(method);
            };
            return proxy(PreparedStatement.class, handler);
        }

        private ResultSet resultSet() {
            InvocationHandler handler = new InvocationHandler() {
                private int cursor = -1;
                private final List<String> columns = rows.isEmpty() ? List.of("id") : new ArrayList<>(rows.get(0).keySet());

                @Override
                public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                    return switch (method.getName()) {
                        case "next" -> ++cursor < rows.size();
                        case "getMetaData" -> metaData(columns);
                        case "getObject" -> rows.get(cursor).get(columns.get((Integer) args[0] - 1));
                        default -> defaultValue(method);
                    };
                }
            };
            return proxy(ResultSet.class, handler);
        }

        private ResultSetMetaData metaData(List<String> columns) {
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "getColumnCount" -> columns.size();
                case "getColumnLabel" -> columns.get((Integer) args[0] - 1);
                default -> defaultValue(method);
            };
            return proxy(ResultSetMetaData.class, handler);
        }

        @SuppressWarnings("unchecked")
        private static <T> T proxy(Class<T> type, InvocationHandler handler) {
            return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
        }

        private static Object defaultValue(Method method) {
            Class<?> returnType = method.getReturnType();
            if (returnType == boolean.class) {
                return false;
            }
            if (returnType == int.class) {
                return 0;
            }
            if (returnType == long.class) {
                return 0L;
            }
            return null;
        }
    }
}
