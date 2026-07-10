/**
 * @Author : Cui
 * @Date: 2026/07/06 21:49
 * @Description DataSmart Govern Backend - SyncObjectExecutionLifecycleSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncObjectExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.entity.SyncTemplate;
import com.czh.datasmart.govern.datasync.mapper.SyncObjectExecutionMapper;
import com.czh.datasmart.govern.datasync.support.SyncObjectExecutionState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * OBJECT_LIST 对象级执行生命周期支撑组件。
 *
 * <p>职责边界：</p>
 * <p>1. 本组件只维护对象级执行账本，不直接调用 datasource-management，也不决定父 execution 最终状态；</p>
 * <p>2. {@link SyncObjectListFanOutDispatchService} 负责调度对象、调用 run-once、根据汇总结果推动父 execution；</p>
 * <p>3. 本组件负责“创建对象级记录、标记运行、标记成功、标记失败/待重试、生成汇总”。</p>
 *
 * <p>为什么拆出这个类：如果把对象级持久化、重试计数、错误摘要和汇总逻辑都堆进 fan-out 服务，单个服务很快会膨胀成
 * 难以维护的大型 Impl。拆分后 fan-out 服务专注“流程编排”，本类专注“对象状态事实”，后续并发分片、对象级恢复 API、
 * 运行报告查询都可以复用这里。</p>
 */
@Component
@RequiredArgsConstructor
public class SyncObjectExecutionLifecycleSupport {

    public static final String WORK_UNIT_TYPE_OBJECT = "OBJECT";
    public static final String WORK_UNIT_TYPE_PARTITION_SHARD = "PARTITION_SHARD";

    /**
     * 对象级账本是内部控制面事实表；普通公开事件和日志不能直接暴露对象名、字段名、SQL、凭据或行样本。
     */
    public static final String PAYLOAD_POLICY =
            "INTERNAL_OBJECT_EXECUTION_NO_ROWS_NO_SQL_NO_CREDENTIALS_DO_NOT_EXPOSE_OBJECT_NAMES_IN_PUBLIC_EVENTS";

    private static final int MAX_ERROR_MESSAGE_LENGTH = 1000;

    private final SyncObjectExecutionMapper objectExecutionMapper;

    /**
     * 初始化父 execution 下的对象级执行记录。
     *
     * <p>该方法具备幂等语义：如果对象级记录已经存在，就复用已有记录；如果缺少某个 ordinal，则补齐缺失记录。
     * 这对 worker 崩溃恢复很重要：父 execution 可能已经执行完前几个对象，恢复后不能重新创建一组重复账本，
     * 更不能让成功对象被误重跑。</p>
     *
     * @param task 当前同步任务，用于冗余 syncTaskId。
     * @param execution 父级 execution，用于继承租户、项目、工作空间和 executionId。
     * @param template 原始 OBJECT_LIST 模板，用于绑定 templateId。
     * @param contract 解析后的对象映射合同，提供稳定 ordinal 和源/目标对象名。
     * @param maxAttemptCount 每个对象允许的最大尝试次数。
     * @return 已存在和新建的对象级执行记录，按 objectOrdinal 排序。
     */
    public List<SyncObjectExecution> initializeObjectExecutions(SyncTask task,
                                                                SyncExecution execution,
                                                                SyncTemplate template,
                                                                SyncObjectMappingExecutionContract contract,
                                                                int maxAttemptCount) {
        List<SyncObjectExecution> existingRows =
                objectExecutionMapper.selectByExecutionId(execution.getId());
        Map<Integer, SyncObjectExecution> existingByOrdinal = existingRows == null
                ? Map.of()
                : existingRows.stream().collect(Collectors.toMap(
                        SyncObjectExecution::getObjectOrdinal,
                        Function.identity(),
                        (left, right) -> left
                ));
        List<SyncObjectExecution> rows = new ArrayList<>(existingRows == null ? List.of() : existingRows);
        for (SyncObjectMappingExecutionItem item : contract.mappings()) {
            if (existingByOrdinal.containsKey(item.ordinal())) {
                continue;
            }
            SyncObjectExecution row = new SyncObjectExecution();
            row.setTenantId(execution.getTenantId());
            row.setProjectId(execution.getProjectId());
            row.setWorkspaceId(execution.getWorkspaceId());
            row.setSyncTaskId(task.getId());
            row.setExecutionId(execution.getId());
            row.setTemplateId(template.getId());
            row.setObjectOrdinal(item.ordinal());
            row.setWorkUnitType(WORK_UNIT_TYPE_OBJECT);
            row.setShardOrPartition(null);
            row.setPartitionStrategy(null);
            row.setPartitionField(null);
            row.setSourceSchemaName(item.sourceSchemaName());
            row.setSourceObjectName(item.sourceObjectName());
            row.setTargetSchemaName(item.targetSchemaName());
            row.setTargetObjectName(item.targetObjectName());
            row.setObjectState(SyncObjectExecutionState.PENDING.name());
            row.setAttemptCount(0);
            row.setMaxAttemptCount(maxAttemptCount);
            row.setRecordsRead(0L);
            row.setRecordsWritten(0L);
            row.setFailedRecordCount(0L);
            row.setPayloadPolicy(PAYLOAD_POLICY);
            row.setCreateTime(LocalDateTime.now());
            row.setUpdateTime(LocalDateTime.now());
            objectExecutionMapper.insert(row);
            rows.add(row);
        }
        rows.sort(Comparator.comparing(SyncObjectExecution::getObjectOrdinal));
        return rows;
    }

    /**
     * 初始化单表分片级执行账本。
     *
     * <p>该方法服务于大表离线同步的 DataX-style 拆分场景：一张源表可能被 partitionConfig 拆成多个 ID range
     * 分片，每个分片都要拥有独立状态、尝试次数和计数。它复用 {@code data_sync_object_execution}，而不是
     * 另建一张 shard 表，原因是“对象级可恢复”和“分片级可恢复”的状态机高度一致：</p>
     * <p>1. 成功工作单元后续恢复时跳过；</p>
     * <p>2. 失败工作单元可以单独重置为 PENDING；</p>
     * <p>3. 父 execution 最终根据全部工作单元聚合为 SUCCEEDED、PARTIALLY_SUCCEEDED 或 FAILED。</p>
     *
     * <p>幂等性说明：如果 worker 在部分分片完成后崩溃，恢复时本方法会复用已存在的分片账本，只补齐缺失分片，
     * 不会重建整批记录，也不会把 SUCCEEDED 分片重置。</p>
     *
     * @param task 当前同步任务。
     * @param execution 父级执行记录。
     * @param template 同步模板。
     * @param contract 已解析的分片合同。
     * @return 当前 execution 下完整的分片账本，按 objectOrdinal 排序。
     */
    public List<SyncObjectExecution> initializePartitionShardExecutions(SyncTask task,
                                                                        SyncExecution execution,
                                                                        SyncTemplate template,
                                                                        SyncPartitionShardExecutionContract contract) {
        List<SyncObjectExecution> existingRows =
                objectExecutionMapper.selectByExecutionId(execution.getId());
        Map<Integer, SyncObjectExecution> existingByOrdinal = existingRows == null
                ? Map.of()
                : existingRows.stream().collect(Collectors.toMap(
                        SyncObjectExecution::getObjectOrdinal,
                        Function.identity(),
                        (left, right) -> left
                ));
        List<SyncObjectExecution> rows = new ArrayList<>(existingRows == null ? List.of() : existingRows);
        for (SyncPartitionShardExecutionItem shard : contract.shards()) {
            if (existingByOrdinal.containsKey(shard.ordinal())) {
                continue;
            }
            SyncObjectExecution row = new SyncObjectExecution();
            row.setTenantId(execution.getTenantId());
            row.setProjectId(execution.getProjectId());
            row.setWorkspaceId(execution.getWorkspaceId());
            row.setSyncTaskId(task.getId());
            row.setExecutionId(execution.getId());
            row.setTemplateId(template.getId());
            row.setObjectOrdinal(shard.ordinal());
            row.setWorkUnitType(WORK_UNIT_TYPE_PARTITION_SHARD);
            row.setShardOrPartition(shard.shardOrPartition());
            row.setPartitionStrategy(shard.partitionStrategy());
            row.setPartitionField(shard.partitionField());
            row.setSourceSchemaName(template.getSourceSchemaName());
            row.setSourceObjectName(template.getSourceObjectName());
            row.setTargetSchemaName(template.getTargetSchemaName());
            row.setTargetObjectName(template.getTargetObjectName());
            row.setObjectState(SyncObjectExecutionState.PENDING.name());
            row.setAttemptCount(0);
            row.setMaxAttemptCount(contract.maxAttemptCount());
            row.setRecordsRead(0L);
            row.setRecordsWritten(0L);
            row.setFailedRecordCount(0L);
            row.setPayloadPolicy(PAYLOAD_POLICY);
            row.setCreateTime(LocalDateTime.now());
            row.setUpdateTime(LocalDateTime.now());
            objectExecutionMapper.insert(row);
            rows.add(row);
        }
        rows.sort(Comparator.comparing(SyncObjectExecution::getObjectOrdinal));
        return rows;
    }

    /**
     * 标记对象进入 RUNNING，并递增对象级尝试次数。
     *
     * <p>attemptCount 在进入 RUNNING 时递增，而不是失败后递增。这样即使 worker 在真实调用后崩溃、来不及写失败结果，
     * 恢复流程仍能看到“这个对象至少尝试过一次”，避免无限重复处理一个不稳定对象。</p>
     *
     * @param row 对象级账本记录。
     * @return 更新后的对象级账本记录。
     */
    public SyncObjectExecution markObjectRunning(SyncObjectExecution row) {
        row.setObjectState(SyncObjectExecutionState.RUNNING.name());
        row.setAttemptCount(safeInt(row.getAttemptCount()) + 1);
        if (row.getStartedAt() == null) {
            row.setStartedAt(LocalDateTime.now());
        }
        row.setFinishedAt(null);
        row.setUpdateTime(LocalDateTime.now());
        objectExecutionMapper.updateById(row);
        return row;
    }

    /**
     * 标记对象成功。
     *
     * <p>成功对象的计数会被父 execution 汇总使用。恢复时如果发现对象已经是 SUCCEEDED，fan-out 会直接跳过真实 run-once 调用。
     * 这正是 DataX-style “成功分片不重跑，失败分片可重试”的基础。</p>
     *
     * @param row 对象级账本记录。
     * @param result datasource-management run-once 返回的低敏执行结果。
     * @return 更新后的对象级账本记录。
     */
    public SyncObjectExecution markObjectSucceeded(SyncObjectExecution row,
                                                   SyncBatchRunOnceRemoteExecutionResult result) {
        row.setObjectState(SyncObjectExecutionState.SUCCEEDED.name());
        row.setRecordsRead(zeroIfNull(result == null ? null : result.totalRecordsRead()));
        row.setRecordsWritten(zeroIfNull(result == null ? null : result.totalRecordsWritten()));
        row.setFailedRecordCount(zeroIfNull(result == null ? null : result.totalFailedRecordCount()));
        row.setLastErrorType(null);
        row.setLastErrorCode(null);
        row.setLastErrorMessage(null);
        row.setFinishedAt(LocalDateTime.now());
        row.setUpdateTime(LocalDateTime.now());
        objectExecutionMapper.updateById(row);
        /*
         * updateById 默认忽略 null。对象从 FAILED 重试为 SUCCEEDED 时必须显式清空旧错误字段，
         * 否则运行详情会出现“状态成功但仍显示 RUNNER_FAILED”的矛盾信息。
         */
        objectExecutionMapper.update(null, new UpdateWrapper<SyncObjectExecution>()
                .eq("id", row.getId())
                .set("last_error_type", null)
                .set("last_error_code", null)
                .set("last_error_message", null));
        return row;
    }

    /**
     * 标记对象失败或进入 RETRYING。
     *
     * <p>如果 retrying=true，表示本次失败只是一轮中间尝试失败，后续 fan-out 还会继续尝试当前对象。
     * 如果 retrying=false，则表示当前对象已到最终 FAILED，父 execution 最终可能变成 PARTIALLY_SUCCEEDED 或 FAILED。</p>
     *
     * @param row 对象级账本记录。
     * @param result datasource-management 返回的低敏失败结果；为空时使用 fallback 字段。
     * @param retrying 本次失败后是否仍要继续自动重试。
     * @param fallbackErrorType 默认低敏错误类型。
     * @param fallbackErrorCode 默认低敏错误码。
     * @param fallbackErrorMessage 默认低敏错误摘要。
     * @return 更新后的对象级账本记录。
     */
    public SyncObjectExecution markObjectFailed(SyncObjectExecution row,
                                                SyncBatchRunOnceRemoteExecutionResult result,
                                                boolean retrying,
                                                String fallbackErrorType,
                                                String fallbackErrorCode,
                                                String fallbackErrorMessage) {
        row.setObjectState(retrying
                ? SyncObjectExecutionState.RETRYING.name()
                : SyncObjectExecutionState.FAILED.name());
        row.setRecordsRead(zeroIfNull(result == null ? row.getRecordsRead() : result.totalRecordsRead()));
        row.setRecordsWritten(zeroIfNull(result == null ? row.getRecordsWritten() : result.totalRecordsWritten()));
        row.setFailedRecordCount(Math.max(1L,
                zeroIfNull(result == null ? row.getFailedRecordCount() : result.totalFailedRecordCount())));
        row.setLastErrorType(firstText(result == null ? null : result.errorType(), fallbackErrorType));
        row.setLastErrorCode(firstText(result == null ? null : result.errorCode(), fallbackErrorCode));
        row.setLastErrorMessage(truncate(firstText(result == null ? null : result.errorMessage(), fallbackErrorMessage),
                MAX_ERROR_MESSAGE_LENGTH));
        row.setFinishedAt(retrying ? null : LocalDateTime.now());
        row.setUpdateTime(LocalDateTime.now());
        objectExecutionMapper.updateById(row);
        return row;
    }

    /**
     * 汇总对象级执行结果，供父 execution 决定终态。
     *
     * <p>这里刻意只汇总低敏计数和错误码，不把对象名、字段名、SQL、where 条件或样本数据放进 summary。
     * 公开 receipt、普通日志和指标应消费 summary，而不是直接消费对象级账本正文。</p>
     *
     * @param rows 对象级执行记录。
     * @return 对象级聚合结果。
     */
    public SyncObjectExecutionSummary summarize(List<SyncObjectExecution> rows) {
        List<SyncObjectExecution> safeRows = rows == null ? List.of() : rows;
        int succeeded = 0;
        int failed = 0;
        int retrying = 0;
        long recordsRead = 0L;
        long recordsWritten = 0L;
        long failedRecordCount = 0L;
        List<String> issueCodes = new ArrayList<>();
        for (SyncObjectExecution row : safeRows) {
            if (SyncObjectExecutionState.SUCCEEDED.name().equals(row.getObjectState())) {
                succeeded++;
                recordsRead += zeroIfNull(row.getRecordsRead());
                recordsWritten += zeroIfNull(row.getRecordsWritten());
                failedRecordCount += zeroIfNull(row.getFailedRecordCount());
            } else if (SyncObjectExecutionState.FAILED.name().equals(row.getObjectState())) {
                failed++;
                failedRecordCount += Math.max(1L, zeroIfNull(row.getFailedRecordCount()));
                if (hasText(row.getLastErrorCode())) {
                    issueCodes.add(row.getLastErrorCode());
                }
            } else if (SyncObjectExecutionState.RETRYING.name().equals(row.getObjectState())
                    || SyncObjectExecutionState.RUNNING.name().equals(row.getObjectState())) {
                retrying++;
            }
        }
        return new SyncObjectExecutionSummary(
                safeRows.size(),
                succeeded,
                failed,
                retrying,
                recordsRead,
                recordsWritten,
                failedRecordCount,
                distinct(issueCodes)
        );
    }

    private List<String> distinct(List<String> values) {
        return new ArrayList<>(new LinkedHashSet<>(values));
    }

    private Integer safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private Long zeroIfNull(Long value) {
        return value == null ? 0L : value;
    }

    private String firstText(String value, String fallback) {
        return hasText(value) ? value.trim() : fallback;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
