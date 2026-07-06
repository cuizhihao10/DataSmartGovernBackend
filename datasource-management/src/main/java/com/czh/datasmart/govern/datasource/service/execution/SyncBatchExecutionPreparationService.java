/**
 * @Author : Cui
 * @Date: 2026/06/20 03:22
 * @Description DataSmart Govern Backend - SyncBatchExecutionPreparationService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.execution;

import com.czh.datasmart.govern.datasource.controller.dto.SyncBatchExecutionPlan;
import com.czh.datasmart.govern.datasource.service.execution.jdbc.SyncJdbcDialect;
import com.czh.datasmart.govern.datasource.service.execution.jdbc.SyncJdbcDialectRegistry;
import com.czh.datasmart.govern.datasource.service.execution.jdbc.SyncJdbcFilterCondition;
import com.czh.datasmart.govern.datasource.service.execution.jdbc.SyncJdbcReadStatementSpec;
import com.czh.datasmart.govern.datasource.service.execution.jdbc.SyncJdbcWriteStatementSpec;
import com.czh.datasmart.govern.datasource.service.execution.jdbc.SyncPreparedJdbcStatement;
import com.czh.datasmart.govern.datasource.support.SyncWriteStrategy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 批处理执行准备服务。
 *
 * <p>该服务承接 5.92 的低敏执行计划和 5.93 的 JDBC 方言层，
 * 把 claim 返回的 `SyncBatchExecutionPlan` 转换为 worker 内部可消费的执行准备包。</p>
 *
 * <p>当前阶段仍然不执行真实数据库读取和写入。
 * 它只做三件事：</p>
 * <p>1. 根据 readPlan 选择源端 JDBC 方言，生成内部读取 PreparedStatement 模板；</p>
 * <p>2. 根据 writePlan 选择目标端 JDBC 方言，生成内部写入 PreparedStatement 模板；</p>
 * <p>3. 汇总 checkpoint、心跳、进度、完成、失败等回调契约，供后续 worker 主循环使用。</p>
 *
 * <p>这个服务是收敛阶段很关键的一层胶水：它让执行器不再各自解释 plan，
 * 但也避免直接把 JDBC 连接、事务和真实行数据塞入控制面。</p>
 */
@Component
@RequiredArgsConstructor
public class SyncBatchExecutionPreparationService {

    private static final String BUNDLE_VERSION = "datasmart.datasource.sync-worker-bundle.v1";
    private static final String EXECUTION_BOUNDARY = "WORKER_INTERNAL_SQL_TEMPLATE_NO_CONNECTION_NO_ROW_DATA";

    /**
     * JDBC 方言注册表。
     */
    private final SyncJdbcDialectRegistry syncJdbcDialectRegistry;

    /**
     * 准备 worker 执行包。
     *
     * @param request 执行准备请求，包含低敏执行计划和字段清单。
     * @return worker 内部执行准备包，包含 read/write context、checkpoint 和 callback plan。
     */
    public SyncBatchWorkerExecutionBundle prepareJdbcExecution(SyncBatchExecutionPreparationRequest request) {
        validateRequest(request);
        SyncBatchExecutionPlan plan = request.getExecutionPlan();
        SyncPreparedJdbcStatement readStatement = buildReadStatement(
                plan.getReadPlan(), request.getSelectedColumns(), request.getWriteColumns(), request.getPrimaryKeyColumns());
        SyncPreparedJdbcStatement writeStatement = buildWriteStatement(plan.getWritePlan(), request.getWriteColumns(), request.getPrimaryKeyColumns());

        return new SyncBatchWorkerExecutionBundle(
                BUNDLE_VERSION,
                EXECUTION_BOUNDARY,
                plan.getTaskId(),
                plan.getExecutionId(),
                buildReadContext(plan, readStatement),
                buildWriteContext(plan, writeStatement, request.getPrimaryKeyColumns()),
                buildCheckpointPlan(plan.getCheckpointPlan()),
                buildCallbackPlan(plan.getRuntimeControlPlan()),
                mergeWarnings(plan),
                LocalDateTime.now()
        );
    }

    /**
     * 构建读取语句。
     */
    private SyncPreparedJdbcStatement buildReadStatement(SyncBatchExecutionPlan.ReadPlan readPlan,
                                                         List<String> selectedColumns,
                                                         List<String> writeColumns,
                                                         List<String> primaryKeyColumns) {
        SyncJdbcDialect dialect = syncJdbcDialectRegistry.getRequiredDialect(readPlan.getConnectorType());
        SyncJdbcReadStatementSpec spec = new SyncJdbcReadStatementSpec(
                readPlan.getObjectLocator(),
                selectedColumns,
                readPlan.getIncrementalField(),
                filterConditions(readPlan),
                stableSortColumns(selectedColumns, writeColumns, primaryKeyColumns),
                readPlan.getReadStrategy(),
                readPlan.getRecommendedFetchSize()
        );
        return switch (readPlan.getReadStrategy()) {
            case "FULL_OBJECT_SCAN" -> dialect.buildFullReadStatement(spec);
            case "INCREMENTAL_TIME_WINDOW", "INCREMENTAL_ID_RANGE" -> dialect.buildIncrementalReadStatement(spec);
            case "REPLAY_FROM_CHECKPOINT", "BACKFILL_PARTITION_RANGE" -> dialect.buildIncrementalReadStatement(spec);
            default -> throw new UnsupportedOperationException("当前 JDBC 准备层暂不支持读取策略: " + readPlan.getReadStrategy());
        };
    }

    /**
     * 构建写入语句。
     */
    private SyncPreparedJdbcStatement buildWriteStatement(SyncBatchExecutionPlan.WritePlan writePlan,
                                                          List<String> writeColumns,
                                                          List<String> primaryKeyColumns) {
        SyncJdbcDialect dialect = syncJdbcDialectRegistry.getRequiredDialect(writePlan.getConnectorType());
        SyncWriteStrategy writeStrategy = SyncWriteStrategy.fromValue(writePlan.getWriteStrategy());
        SyncJdbcWriteStatementSpec spec = new SyncJdbcWriteStatementSpec(
                writePlan.getObjectLocator(),
                writeColumns,
                resolvePrimaryKeyColumns(writePlan, primaryKeyColumns),
                writeStrategy,
                writePlan.getRecommendedWriteBatchSize()
        );
        if (writeStrategy == SyncWriteStrategy.APPEND) {
            return dialect.buildAppendWriteStatement(spec);
        }
        return dialect.buildConflictAwareWriteStatement(spec);
    }

    /**
     * 构建读取上下文。
     */
    private SyncBatchReadContext buildReadContext(SyncBatchExecutionPlan plan, SyncPreparedJdbcStatement readStatement) {
        Map<String, Object> parameterValues = new HashMap<>();
        parameterValues.put("limit", plan.getReadPlan().getRecommendedFetchSize());
        parameterValues.put("offset", 0L);
        List<SyncBatchExecutionPlan.ReadFilterCondition> conditions = plan.getReadPlan().getFilterConditions();
        if (conditions != null) {
            for (int index = 0; index < conditions.size(); index++) {
                SyncBatchExecutionPlan.ReadFilterCondition condition = conditions.get(index);
                if (condition != null && Boolean.TRUE.equals(condition.getValueRequired())) {
                    parameterValues.put("filter_" + index, condition.getValue());
                }
            }
        }
        return new SyncBatchReadContext(
                plan.getTaskId(),
                plan.getExecutionId(),
                plan.getReadPlan().getDatasourceId(),
                plan.getCheckpointPlan().getCheckpointType(),
                plan.getReadPlan().getRecommendedFetchSize(),
                readStatement,
                parameterValues
        );
    }

    /**
     * 将执行计划中的 internal 过滤条件转换为 JDBC 方言规格。
     *
     * <p>这里不拼接 SQL，只复制结构化字段。字段名、操作符和参数顺序仍由方言层二次校验和生成。</p>
     */
    private List<SyncJdbcFilterCondition> filterConditions(SyncBatchExecutionPlan.ReadPlan readPlan) {
        if (readPlan.getFilterConditions() == null || readPlan.getFilterConditions().isEmpty()) {
            return List.of();
        }
        return readPlan.getFilterConditions().stream()
                .map(condition -> new SyncJdbcFilterCondition(
                        condition.getColumn(),
                        condition.getOperator(),
                        condition.getValue(),
                        condition.getValueRequired()
                ))
                .toList();
    }

    /**
     * 根据目标端主键反推出源端稳定排序列。
     *
     * <p>字段映射允许 {@code sourceField -> targetField} 改名，例如源端 {@code customer_id} 写入目标端 {@code id}。
     * 如果全量多批次扫描直接拿目标端主键 {@code id} 去源端排序，源端 SQL 会找不到列。
     * 因此这里按字段映射顺序把目标主键映射回源字段，作为 reader 的稳定排序列。</p>
     */
    private List<String> stableSortColumns(List<String> selectedColumns,
                                           List<String> writeColumns,
                                           List<String> primaryKeyColumns) {
        if (selectedColumns == null || selectedColumns.isEmpty()) {
            return List.of();
        }
        if (primaryKeyColumns == null || primaryKeyColumns.isEmpty()
                || writeColumns == null || writeColumns.isEmpty()) {
            return List.copyOf(selectedColumns);
        }
        List<String> sourceSortColumns = new ArrayList<>();
        for (String primaryKeyColumn : primaryKeyColumns) {
            int index = writeColumns.indexOf(primaryKeyColumn);
            if (index >= 0 && index < selectedColumns.size()) {
                sourceSortColumns.add(selectedColumns.get(index));
            }
        }
        return sourceSortColumns.isEmpty() ? List.copyOf(selectedColumns) : List.copyOf(sourceSortColumns);
    }

    /**
     * 构建写入上下文。
     */
    private SyncBatchWriteContext buildWriteContext(SyncBatchExecutionPlan plan,
                                                    SyncPreparedJdbcStatement writeStatement,
                                                    List<String> primaryKeyColumns) {
        return new SyncBatchWriteContext(
                plan.getTaskId(),
                plan.getExecutionId(),
                plan.getWritePlan().getDatasourceId(),
                plan.getWritePlan().getWriteStrategy(),
                plan.getWritePlan().getRecommendedWriteBatchSize(),
                plan.getWritePlan().getRecommendedCommitIntervalRecords(),
                resolvePrimaryKeyColumns(plan.getWritePlan(), primaryKeyColumns),
                writeStatement
        );
    }

    /**
     * 构建 checkpoint 计划。
     */
    private SyncBatchWorkerCheckpointPlan buildCheckpointPlan(SyncBatchExecutionPlan.CheckpointPlan checkpointPlan) {
        return new SyncBatchWorkerCheckpointPlan(
                checkpointPlan.getCheckpointType(),
                checkpointPlan.getInitialCheckpointPolicy(),
                checkpointPlan.getResumeRequired(),
                checkpointPlan.getShardAware(),
                checkpointPlan.getPersistEveryRecords(),
                checkpointPlan.getCheckpointValueVisibility()
        );
    }

    /**
     * 构建回调计划。
     */
    private SyncBatchWorkerCallbackPlan buildCallbackPlan(SyncBatchExecutionPlan.RuntimeControlPlan runtimeControlPlan) {
        return new SyncBatchWorkerCallbackPlan(
                runtimeControlPlan.getExecutorId(),
                runtimeControlPlan.getLeaseExpireAt(),
                runtimeControlPlan.getHeartbeatRequired(),
                runtimeControlPlan.getRequiredCallbacks(),
                runtimeControlPlan.getIdempotencyScope(),
                runtimeControlPlan.getTimeoutSeconds(),
                runtimeControlPlan.getMaxRetryCount()
        );
    }

    /**
     * 主键字段兜底。
     */
    private List<String> resolvePrimaryKeyColumns(SyncBatchExecutionPlan.WritePlan writePlan, List<String> primaryKeyColumns) {
        if (primaryKeyColumns != null && !primaryKeyColumns.isEmpty()) {
            return primaryKeyColumns;
        }
        if (writePlan.getPrimaryKeyField() != null && !writePlan.getPrimaryKeyField().isBlank()) {
            return List.of(writePlan.getPrimaryKeyField());
        }
        return List.of();
    }

    /**
     * 汇总执行前警告。
     */
    private List<String> mergeWarnings(SyncBatchExecutionPlan plan) {
        List<String> warnings = new ArrayList<>();
        if (plan.getWarnings() != null) {
            warnings.addAll(plan.getWarnings());
        }
        warnings.add("当前准备包包含内部 SQL 模板，只能在 worker/dialect 层使用，不能返回给普通管理 API 或低敏事件投影");
        return warnings;
    }

    /**
     * 请求基础校验。
     */
    private void validateRequest(SyncBatchExecutionPreparationRequest request) {
        if (request == null || request.getExecutionPlan() == null) {
            throw new IllegalArgumentException("executionPlan 不能为空");
        }
        SyncBatchExecutionPlan plan = request.getExecutionPlan();
        if (plan.getReadPlan() == null || plan.getWritePlan() == null
                || plan.getCheckpointPlan() == null || plan.getRuntimeControlPlan() == null) {
            throw new IllegalArgumentException("executionPlan 必须包含 readPlan、writePlan、checkpointPlan 和 runtimeControlPlan");
        }
        if (request.getWriteColumns() == null || request.getWriteColumns().isEmpty()) {
            throw new IllegalArgumentException("writeColumns 不能为空，写入准备必须显式声明目标端字段顺序");
        }
    }
}
