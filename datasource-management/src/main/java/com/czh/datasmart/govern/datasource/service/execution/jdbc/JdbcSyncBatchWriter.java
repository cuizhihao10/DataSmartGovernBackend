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
import com.czh.datasmart.govern.datasource.service.execution.SyncDirtyRecordSample;
import com.czh.datasmart.govern.datasource.service.execution.SyncBatchWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
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
 * <p>4. 批写失败后会进入行级隔离模式：成功行可以继续提交，失败行转成结构化脏样本交给 data-sync 落库。</p>
 */
@Component
@RequiredArgsConstructor
public class JdbcSyncBatchWriter implements SyncBatchWriter {

    private static final int MAX_DIRTY_SAMPLE_COUNT = 20;
    private static final int MAX_ERROR_SUMMARY_LENGTH = 500;

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
                return new SyncBatchWriteResult(recordsWritten, recordBatch.size() - recordsWritten, true, null,
                        List.of(), false);
            }
        } catch (BatchUpdateException exception) {
            rollbackQuietly(connection);
            return isolateDirtyRows(context, recordBatch, exception);
        } catch (ClassNotFoundException | SQLException | RuntimeException exception) {
            rollbackQuietly(connection);
            return isolateDirtyRows(context, recordBatch, exception);
        } finally {
            closeQuietly(connection);
        }
    }

    /**
     * 批写失败后的行级隔离。
     *
     * <p>JDBC batch 失败时，驱动通常只能告诉我们“这一批失败了”，不一定稳定告诉具体哪一行、哪个字段。
     * DataX 的处理思路是允许脏数据采集和阈值控制，而不是一条坏数据直接毁掉全部成功数据。
     * 因此这里回滚原批次后进入逐行写入：</p>
     * <p>1. 能写成功的行继续提交，避免因为少量坏数据导致整批吞吐归零；</p>
     * <p>2. 写失败的行转成 {@link SyncDirtyRecordSample}，只保存低敏摘要、字段集合和行哈希；</p>
     * <p>3. 是否超过脏数据阈值由 run-once 根据 RuntimeControlPlan 统一裁决。</p>
     */
    private SyncBatchWriteResult isolateDirtyRows(SyncBatchWriteContext context,
                                                  SyncBatchRecordBatch recordBatch,
                                                  Exception batchException) {
        Connection isolationConnection = null;
        long recordsWritten = 0L;
        long failedRecords = 0L;
        List<SyncDirtyRecordSample> dirtySamples = new ArrayList<>();
        try {
            isolationConnection = syncJdbcConnectionProvider.openConnection(context.getDatasourceId(), false);
            isolationConnection.setReadOnly(false);
            isolationConnection.setAutoCommit(false);
            try (PreparedStatement preparedStatement = isolationConnection.prepareStatement(context.getWriteStatement().getSql())) {
                for (int rowIndex = 0; rowIndex < recordBatch.getRows().size(); rowIndex++) {
                    Map<String, Object> row = recordBatch.getRows().get(rowIndex);
                    /*
                     * PostgreSQL 在事务内某一行写入失败后，会把整个事务标记为 aborted。
                     * 如果不回滚到失败语句之前的 savepoint，后续好行也会被连带判成失败。
                     *
                     * DataX-style dirty record 的目标是“少量坏行隔离为脏样本，好行继续提交”，
                     * 所以每一行都用一个 savepoint 包住：
                     * 1. 行成功：释放 savepoint，保留当前行写入；
                     * 2. 行失败：回滚到 savepoint，只撤销当前坏行，不污染后续行；
                     * 3. 批次结束：统一 commit 本批所有成功行。
                     *
                     * savepoint 是 writer 内部事务机制，不进入响应、审计或公开日志。
                     */
                    Savepoint rowSavepoint = isolationConnection.setSavepoint("datasmart_dirty_row_" + rowIndex);
                    try {
                        bindRow(preparedStatement, context.getWriteStatement().getParameterNames(), row);
                        preparedStatement.executeUpdate();
                        releaseSavepointQuietly(isolationConnection, rowSavepoint);
                        recordsWritten++;
                    } catch (SQLException | RuntimeException rowException) {
                        rollbackToSavepointQuietly(isolationConnection, rowSavepoint);
                        failedRecords++;
                        if (dirtySamples.size() < MAX_DIRTY_SAMPLE_COUNT) {
                            dirtySamples.add(toDirtySample(context, rowIndex, row, rowException));
                        }
                    }
                }
                isolationConnection.commit();
            }
            String summary = failedRecords > 0L
                    ? "批写失败后已执行行级隔离，dirtyRecords=" + failedRecords
                    : null;
            return new SyncBatchWriteResult(recordsWritten, failedRecords, true, summary, dirtySamples, false);
        } catch (ClassNotFoundException | SQLException | RuntimeException isolationException) {
            rollbackQuietly(isolationConnection);
            String summary = "批写失败且行级隔离失败: "
                    + firstText(truncateMessage(isolationException.getMessage()), truncateMessage(batchException.getMessage()));
            return new SyncBatchWriteResult(0L, (long) recordBatch.size(), false, summary, dirtySamples, true);
        } finally {
            closeQuietly(isolationConnection);
        }
    }

    private SyncDirtyRecordSample toDirtySample(SyncBatchWriteContext context,
                                                int rowIndex,
                                                Map<String, Object> row,
                                                Exception exception) {
        String errorType = classifyError(exception);
        String message = truncateMessage(exception.getMessage());
        String rowHash = rowHash(row);
        String sourceRecordKey = sourceRecordKey(context, row, rowIndex, rowHash);
        return new SyncDirtyRecordSample(
                errorType,
                sqlStateOrClass(exception),
                message,
                sourceRecordKey,
                null,
                samplePayload(row, rowHash, sourceRecordKey),
                retryable(errorType)
        );
    }

    /**
     * 生成脏数据修复重放可使用的源端记录定位。
     *
     * <p>DataX 的 dirty record 通常会记录坏数据摘要，方便人工排查；但本项目还要支持“修复后重放”。
     * 如果只保存 rowIndex 或 rowHash，系统无法从源端重新读取同一条记录，因为 rowIndex 会随排序、过滤和批次变化，
     * rowHash 也不是数据库可查询字段。因此当写入计划声明了主键字段时，这里会优先写入结构化主键定位：</p>
     *
     * <pre>
     * {"strategy":"PRIMARY_KEY_EQ","column":"id","value":1001,"valueType":"Long"}
     * </pre>
     *
     * <p>该 JSON 不包含完整行数据、SQL、连接串或凭据；后续 data-sync 只能把它转换成
     * {@code column EQ ?} 这种 PreparedStatement 参数条件，不能直接把 JSON 拼成 SQL。</p>
     */
    private String sourceRecordKey(SyncBatchWriteContext context,
                                   Map<String, Object> row,
                                   int rowIndex,
                                   String rowHash) {
        List<String> primaryKeyColumns = context == null || context.getPrimaryKeyColumns() == null
                ? List.of()
                : context.getPrimaryKeyColumns().stream()
                .filter(column -> column != null && !column.isBlank())
                .toList();
        if (row != null && primaryKeyColumns.size() == 1) {
            String column = primaryKeyColumns.get(0).trim();
            if (row.containsKey(column) && row.get(column) != null) {
                Object value = row.get(column);
                return "{\"strategy\":\"PRIMARY_KEY_EQ\",\"column\":\"" + jsonEscape(column)
                        + "\",\"value\":" + jsonValue(value)
                        + ",\"valueType\":\"" + jsonEscape(value.getClass().getSimpleName()) + "\"}";
            }
        }
        return "{\"strategy\":\"ROW_DIAGNOSTIC_ONLY\",\"rowIndex\":" + rowIndex
                + ",\"rowHash\":\"" + jsonEscape(rowHash) + "\"}";
    }

    /**
     * 构造低敏样本 payload。
     *
     * <p>payload 只记录字段名集合、rowHash 和 sourceRecordKey。字段名能帮助用户定位“哪个字段集合参与了写入”，
     * rowHash 能帮助日志侧关联，sourceRecordKey 则给后续修复重放提供机器可读定位；完整业务值仍然不落库。</p>
     */
    private String samplePayload(Map<String, Object> row, String rowHash, String sourceRecordKey) {
        String columns = row == null ? "" : String.join(",", row.keySet());
        return "{\"columns\":\"" + jsonEscape(columns)
                + "\",\"rowHash\":\"" + jsonEscape(rowHash)
                + "\",\"sourceRecordKey\":" + sourceRecordKey + "}";
    }

    private String classifyError(Exception exception) {
        if (exception instanceof SQLException sqlException) {
            String state = sqlException.getSQLState();
            int code = sqlException.getErrorCode();
            String message = sqlException.getMessage() == null ? "" : sqlException.getMessage().toLowerCase();
            if ("23505".equals(state) || code == 1062 || message.contains("duplicate")) {
                return "DUPLICATE_KEY";
            }
            if ("23502".equals(state) || code == 1048 || message.contains("not null")) {
                return "NOT_NULL_VIOLATION";
            }
            if (state != null && state.startsWith("22") || code == 1292 || message.contains("incorrect")
                    || message.contains("invalid")) {
                return "TYPE_CONVERSION_ERROR";
            }
        }
        if (exception instanceof IllegalArgumentException) {
            return "FIELD_MAPPING_ERROR";
        }
        return "TARGET_WRITE_ERROR";
    }

    private String sqlStateOrClass(Exception exception) {
        if (exception instanceof SQLException sqlException && sqlException.getSQLState() != null) {
            return sqlException.getSQLState();
        }
        return exception == null ? "UNKNOWN" : exception.getClass().getSimpleName();
    }

    private Boolean retryable(String errorType) {
        return !"FIELD_MAPPING_ERROR".equals(errorType) && !"TYPE_CONVERSION_ERROR".equals(errorType);
    }

    private String rowHash(Map<String, Object> row) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(String.valueOf(row).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes).substring(0, 16);
        } catch (NoSuchAlgorithmException exception) {
            return "hash-unavailable";
        }
    }

    private String jsonValue(Object value) {
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return "\"" + jsonEscape(String.valueOf(value)) + "\"";
    }

    private String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
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
    /**
     * 回滚到行级 savepoint，隔离当前坏行对事务的影响。
     *
     * <p>如果这里失败，通常意味着连接或事务本身已经不可恢复；主错误已经被转换成 dirty sample，
     * 外层事务最终 commit/close 仍会给出受控失败摘要。因此这里不把驱动细节拼进低敏响应，
     * 避免泄露 SQL、连接或数据库内部信息。</p>
     */
    private void rollbackToSavepointQuietly(Connection connection, Savepoint savepoint) {
        if (connection == null || savepoint == null) {
            return;
        }
        try {
            connection.rollback(savepoint);
        } catch (SQLException ignored) {
            // 行级回滚失败不覆盖原始坏行错误，保持脏数据诊断的主因清晰。
        }
    }

    /**
     * 释放成功行的 savepoint，减少大批量写入时数据库端事务元数据占用。
     *
     * <p>释放 savepoint 属于资源优化，不改变业务结果；如果驱动不支持或释放失败，
     * 当前行仍然已经成功写入，后续统一 commit 即可。</p>
     */
    private void releaseSavepointQuietly(Connection connection, Savepoint savepoint) {
        if (connection == null || savepoint == null) {
            return;
        }
        try {
            connection.releaseSavepoint(savepoint);
        } catch (SQLException ignored) {
            // savepoint 释放失败不影响当前事务后续提交，保持低噪声处理。
        }
    }

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
        return message.length() > MAX_ERROR_SUMMARY_LENGTH ? message.substring(0, MAX_ERROR_SUMMARY_LENGTH) : message;
    }

    private String firstText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
