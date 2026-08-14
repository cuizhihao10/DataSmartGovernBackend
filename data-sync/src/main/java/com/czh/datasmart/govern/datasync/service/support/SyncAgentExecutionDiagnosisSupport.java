/**
 * @Author : Cui
 * @Date: 2026/07/22
 * @Description DataSmart Govern Backend - SyncAgentExecutionDiagnosisSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionDiagnosisResponse;
import com.czh.datasmart.govern.datasync.entity.SyncErrorSample;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncExecutionPolicySnapshot;
import com.czh.datasmart.govern.datasync.entity.SyncIncidentRecord;
import com.czh.datasmart.govern.datasync.entity.SyncObjectExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.entity.SyncTaskDefinition;
import com.czh.datasmart.govern.datasync.mapper.SyncErrorSampleMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionPolicySnapshotMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncIncidentRecordMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncObjectExecutionMapper;
import com.czh.datasmart.govern.datasync.integration.datasource.DatasourceCapabilitySnapshotClient;
import com.czh.datasmart.govern.datasync.integration.datasource.DatasourceCapabilitySnapshotView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 把真实执行事实聚合为有界、低敏且可复核的 Agent 诊断包。
 *
 * <p>本组件只使用持久 execution、对象账本和错误样本做确定性分类。模型可以依据根因码选择受治理动作，
 * 但不能编造任务状态、失败分片数、约束类型或可重试脏数据数量。</p>
 */
@Component
@RequiredArgsConstructor
public class SyncAgentExecutionDiagnosisSupport {

    private static final int MAX_FAILED_OBJECTS = 20;
    private static final int MAX_ERROR_SAMPLES = 100;
    private static final int MAX_CASES = 5;
    private static final Set<String> SENSITIVE_MARKERS = Set.of(
            "jdbc:", "password", "passwd", "token", "secret", "credential",
            "select ", "insert ", "update ", "delete ", " where ");

    private final SyncExecutionMapper executionMapper;
    private final SyncObjectExecutionMapper objectExecutionMapper;
    private final SyncErrorSampleMapper errorSampleMapper;
    private final SyncIncidentRecordMapper incidentRecordMapper;
    private final SyncExecutionPolicySnapshotMapper policySnapshotMapper;
    private final DatasourceCapabilitySnapshotClient capabilitySnapshotClient;

    /**
     * 聚合一次同步失败的结构化事实、策略差异、连接器能力和历史事故证据。
     *
     * <p>该方法只读取平台持久事实和 datasource-management 的低敏能力快照，
     * 不读取连接串、凭据、SQL、样本行或原始日志。能力服务临时不可用时会返回
     * 明确的 UNAVAILABLE 摘要，而不会阻断已有执行账本的诊断。</p>
     */
    public SyncExecutionDiagnosisResponse diagnose(SyncTask task,
                                                    SyncTaskDefinition definition,
                                                    Long requestedExecutionId,
                                                    SyncActorContext actorContext) {
        SyncExecution execution = loadExecution(task, requestedExecutionId);
        List<SyncObjectExecution> allObjects = objectExecutionMapper.selectByExecutionId(execution.getId());
        List<SyncObjectExecution> allFailedObjects = allObjects == null ? List.of() : allObjects.stream()
                .filter(item -> "FAILED".equalsIgnoreCase(item.getObjectState()))
                .toList();
        List<SyncObjectExecution> failedObjects = allFailedObjects.stream()
                .limit(MAX_FAILED_OBJECTS)
                .toList();
        List<SyncErrorSample> samples = errorSampleMapper.selectList(new LambdaQueryWrapper<SyncErrorSample>()
                .eq(SyncErrorSample::getSyncTaskId, task.getId())
                .eq(SyncErrorSample::getExecutionId, execution.getId())
                .orderByDesc(SyncErrorSample::getId)
                .last("LIMIT " + MAX_ERROR_SAMPLES));
        samples = samples == null ? List.of() : samples;

        List<SyncExecutionDiagnosisResponse.ErrorSummary> errors = aggregateErrors(failedObjects, samples);
        List<String> rootCauses = classify(errors, execution);
        List<String> repairActions = repairActions(rootCauses, failedObjects, samples);
        List<SyncExecutionDiagnosisResponse.KnowledgeCaseSummary> cases = similarCases(task, rootCauses);
        SyncExecutionDiagnosisResponse.RuntimeMetricSummary runtimeMetrics = runtimeMetrics(
                execution, allFailedObjects, samples);
        SyncExecutionDiagnosisResponse.ExecutionPolicyComparison policyComparison = policyComparison(task, execution);
        List<SyncExecutionDiagnosisResponse.ConnectorRuntimeSummary> connectorSummaries = connectorSummaries(
                definition, actorContext, policyComparison.current());
        List<SyncExecutionDiagnosisResponse.EvidenceRecord> evidenceRecords = evidenceRecords(
                task, execution, runtimeMetrics, policyComparison, connectorSummaries, cases);
        String ragQuery = ragQuery(definition, rootCauses, errors);
        String digest = sha256(task.getId() + "|" + execution.getId() + "|"
                + String.join(",", rootCauses) + "|" + String.join(",", repairActions) + "|"
                + evidenceRecords.stream().map(SyncExecutionDiagnosisResponse.EvidenceRecord::evidenceId)
                .reduce((left, right) -> left + "," + right).orElse("NO_EVIDENCE"));

        return new SyncExecutionDiagnosisResponse(
                task.getId(), execution.getId(), task.getCurrentState(),
                execution.getExecutionState(), definition.getSyncMode(), definition.getWriteStrategy(),
                definition.getSourceConnectorType(), definition.getTargetConnectorType(),
                definition.getSourceDatasourceId(), definition.getTargetDatasourceId(),
                zero(execution.getRecordsRead()), zero(execution.getRecordsWritten()),
                zero(execution.getFailedRecordCount()), allFailedObjects.size(),
                (int) samples.stream().filter(item -> Boolean.TRUE.equals(item.getRetryable()))
                        .filter(item -> !"QUARANTINED".equalsIgnoreCase(item.getResolutionStatus())).count(),
                (int) samples.stream().filter(item -> "QUARANTINED".equalsIgnoreCase(item.getResolutionStatus())).count(),
                runtimeMetrics, policyComparison, connectorSummaries,
                failedObjects.stream().map(this::failedObjectSummary).toList(),
                errors, rootCauses, repairActions, cases, evidenceRecords, ragQuery, digest,
                "LOW_SENSITIVE_DIAGNOSIS_NO_SQL_NO_CREDENTIALS_NO_SOURCE_KEYS_NO_SAMPLE_PAYLOAD"
        );
    }

    /** 将执行计数和失败账本汇总成模型可直接比较的结构化指标。 */
    private SyncExecutionDiagnosisResponse.RuntimeMetricSummary runtimeMetrics(
            SyncExecution execution,
            List<SyncObjectExecution> failedObjects,
            List<SyncErrorSample> samples) {
        int retryable = (int) samples.stream()
                .filter(item -> Boolean.TRUE.equals(item.getRetryable()))
                .filter(item -> !"QUARANTINED".equalsIgnoreCase(item.getResolutionStatus()))
                .count();
        int quarantined = (int) samples.stream()
                .filter(item -> "QUARANTINED".equalsIgnoreCase(item.getResolutionStatus()))
                .count();
        return new SyncExecutionDiagnosisResponse.RuntimeMetricSummary(
                zero(execution.getRecordsRead()),
                zero(execution.getRecordsWritten()),
                zero(execution.getFailedRecordCount()),
                failedObjects.size(),
                retryable,
                quarantined);
    }

    /**
     * 对比当前失败 execution 与上一次成功 execution 的真实策略快照。
     *
     * <p>只比较持久化的生效参数，不拿“当前管理员配置”冒充历史运行配置。没有快照或
     * 没有历史成功执行时返回明确状态，模型因此不会凭空推测一次调参是否导致故障。</p>
     */
    private SyncExecutionDiagnosisResponse.ExecutionPolicyComparison policyComparison(
            SyncTask task,
            SyncExecution execution) {
        SyncExecutionPolicySnapshot current = loadPolicySnapshot(task.getId(), execution.getId());
        SyncExecution previousExecution = executionMapper.selectOne(new LambdaQueryWrapper<SyncExecution>()
                .eq(SyncExecution::getSyncTaskId, task.getId())
                .eq(SyncExecution::getExecutionState, "SUCCEEDED")
                .lt(SyncExecution::getId, execution.getId())
                .orderByDesc(SyncExecution::getId)
                .last("LIMIT 1"));
        SyncExecutionPolicySnapshot previous = previousExecution == null
                ? null
                : loadPolicySnapshot(task.getId(), previousExecution.getId());
        String status;
        if (current == null) {
            status = "CURRENT_POLICY_SNAPSHOT_MISSING";
        } else if (previousExecution == null) {
            status = "NO_PREVIOUS_SUCCESSFUL_EXECUTION";
        } else if (previous == null) {
            status = "PREVIOUS_SUCCESSFUL_POLICY_SNAPSHOT_MISSING";
        } else {
            status = "COMPARISON_AVAILABLE";
        }
        return new SyncExecutionDiagnosisResponse.ExecutionPolicyComparison(
                status,
                policySummary(current),
                policySummary(previous),
                changedPolicyFields(current, previous));
    }

    /** 按任务和 execution 精确读取一份低敏策略快照。 */
    private SyncExecutionPolicySnapshot loadPolicySnapshot(Long taskId, Long executionId) {
        if (taskId == null || executionId == null) {
            return null;
        }
        return policySnapshotMapper.selectOne(new LambdaQueryWrapper<SyncExecutionPolicySnapshot>()
                .eq(SyncExecutionPolicySnapshot::getSyncTaskId, taskId)
                .eq(SyncExecutionPolicySnapshot::getExecutionId, executionId)
                .last("LIMIT 1"));
    }

    /** 将数据库快照裁剪成不含 snapshotJson 的 Agent 事实。 */
    private SyncExecutionDiagnosisResponse.PolicySnapshotSummary policySummary(
            SyncExecutionPolicySnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        return new SyncExecutionDiagnosisResponse.PolicySnapshotSummary(
                snapshot.getExecutionId(),
                truncate(snapshot.getPolicyCodeSummary(), 500),
                snapshot.getResolvedChannel(),
                snapshot.getTaskGroupSize(),
                snapshot.getReadBatchSize(),
                snapshot.getWriteBatchSize(),
                snapshot.getCommitIntervalRecords(),
                snapshot.getTimeoutSeconds(),
                snapshot.getMaxRetryCount(),
                snapshot.getMaxDirtyRecordCount(),
                snapshot.getMaxDirtyRecordRatio(),
                snapshot.getUpdateTime());
    }

    /** 返回发生变化的策略字段名，不把 JSON 快照正文送给模型。 */
    private List<String> changedPolicyFields(SyncExecutionPolicySnapshot current,
                                             SyncExecutionPolicySnapshot previous) {
        if (current == null || previous == null) {
            return List.of();
        }
        List<String> changed = new ArrayList<>();
        addChanged(changed, "resolvedChannel", current.getResolvedChannel(), previous.getResolvedChannel());
        addChanged(changed, "taskGroupSize", current.getTaskGroupSize(), previous.getTaskGroupSize());
        addChanged(changed, "readBatchSize", current.getReadBatchSize(), previous.getReadBatchSize());
        addChanged(changed, "writeBatchSize", current.getWriteBatchSize(), previous.getWriteBatchSize());
        addChanged(changed, "commitIntervalRecords", current.getCommitIntervalRecords(), previous.getCommitIntervalRecords());
        addChanged(changed, "timeoutSeconds", current.getTimeoutSeconds(), previous.getTimeoutSeconds());
        addChanged(changed, "maxRetryCount", current.getMaxRetryCount(), previous.getMaxRetryCount());
        addChanged(changed, "maxDirtyRecordCount", current.getMaxDirtyRecordCount(), previous.getMaxDirtyRecordCount());
        addChanged(changed, "maxDirtyRecordRatio", current.getMaxDirtyRecordRatio(), previous.getMaxDirtyRecordRatio());
        return List.copyOf(changed);
    }

    /** 只有值确实不同才记录字段名，避免把相同配置误报为变化。 */
    private void addChanged(List<String> changed, String field, Object current, Object previous) {
        if (!Objects.equals(current, previous)) {
            changed.add(field);
        }
    }

    /**
     * 读取源端和目标端低敏能力快照，并附上当前 execution 的实际限流参数。
     *
     * <p>连接器版本、能力和性能建议来自 datasource-management；channel、batch、timeout
     * 来自当前 execution 策略快照。两者均不可用时返回明确状态，不阻断基础诊断。</p>
     */
    private List<SyncExecutionDiagnosisResponse.ConnectorRuntimeSummary> connectorSummaries(
            SyncTaskDefinition definition,
            SyncActorContext actorContext,
            SyncExecutionDiagnosisResponse.PolicySnapshotSummary currentPolicy) {
        return List.of(
                connectorSummary("SOURCE", definition.getSourceDatasourceId(), actorContext, currentPolicy),
                connectorSummary("TARGET", definition.getTargetDatasourceId(), actorContext, currentPolicy));
    }

    /** 构建一端连接器的低敏运行摘要；跨服务失败时只保留稳定失败状态。 */
    private SyncExecutionDiagnosisResponse.ConnectorRuntimeSummary connectorSummary(
            String role,
            Long datasourceId,
            SyncActorContext actorContext,
            SyncExecutionDiagnosisResponse.PolicySnapshotSummary currentPolicy) {
        DatasourceCapabilitySnapshotView snapshot = null;
        String lookupStatus = "AVAILABLE";
        if (datasourceId == null) {
            lookupStatus = "DATASOURCE_ID_MISSING";
        } else {
            try {
                snapshot = capabilitySnapshotClient.getSnapshot(datasourceId, actorContext);
                if (snapshot == null) {
                    lookupStatus = "CAPABILITY_SNAPSHOT_EMPTY";
                }
            } catch (RuntimeException ignored) {
                lookupStatus = "CAPABILITY_LOOKUP_UNAVAILABLE";
            }
        }
        List<String> issueCodes = new ArrayList<>();
        if (snapshot != null && snapshot.getIssueCodes() != null) {
            issueCodes.addAll(snapshot.getIssueCodes());
        }
        if (snapshot == null || "UNAVAILABLE".equalsIgnoreCase(snapshot.getConnectorRuntimeVersion())) {
            issueCodes.add("CONNECTOR_RUNTIME_VERSION_UNAVAILABLE");
        }
        if (!"AVAILABLE".equals(lookupStatus)) {
            issueCodes.add(lookupStatus);
        }
        String limitStatus = currentPolicy == null
                ? "EXECUTION_POLICY_SNAPSHOT_MISSING"
                : "EXECUTION_POLICY_SNAPSHOT_AVAILABLE";
        return new SyncExecutionDiagnosisResponse.ConnectorRuntimeSummary(
                role,
                datasourceId,
                lookupStatus,
                snapshot == null ? null : snapshot.getSnapshotVersion(),
                snapshot == null ? "UNAVAILABLE" : snapshot.getConnectorRuntimeVersion(),
                snapshot == null ? "CAPABILITY_LOOKUP_UNAVAILABLE" : snapshot.getConnectorRuntimeVersionSource(),
                snapshot == null ? null : snapshot.getConnectorType(),
                snapshot == null ? null : snapshot.getConnectorFamily(),
                snapshot == null ? null : snapshot.getImplementationStage(),
                snapshot == null ? null : snapshot.getHealthStatus(),
                snapshot == null ? null : snapshot.getCanRead(),
                snapshot == null ? null : snapshot.getCanWrite(),
                snapshot == null ? null : snapshot.getSupportsSchemaDiscovery(),
                snapshot == null ? null : snapshot.getSupportsFieldMapping(),
                snapshot == null ? null : snapshot.getSupportsCheckpointResume(),
                snapshot == null ? null : snapshot.getSupportsPartitionParallelism(),
                limitStatus,
                currentPolicy == null ? null : currentPolicy.resolvedChannel(),
                currentPolicy == null ? null : currentPolicy.readBatchSize(),
                currentPolicy == null ? null : currentPolicy.writeBatchSize(),
                currentPolicy == null ? null : currentPolicy.timeoutSeconds(),
                "POLICY_GOVERNED_NO_HARD_CONNECTOR_CAPACITY_DECLARED",
                safeList(snapshot == null ? null : snapshot.getConsistencyNotes()),
                safeList(snapshot == null ? null : snapshot.getPerformanceRecommendations()),
                safeList(snapshot == null ? null : snapshot.getProductionLimitations()),
                issueCodes.stream().distinct().limit(32).toList(),
                snapshot == null ? null : snapshot.getGeneratedAt());
    }

    /** 把外部列表规整为有界不可变集合，避免 null 和超长建议进入 Agent 上下文。 */
    private List<String> safeList(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().filter(Objects::nonNull).map(value -> truncate(value, 400))
                .filter(value -> value != null && !value.isBlank()).distinct().limit(32).toList();
    }

    /**
     * 为运行指标、策略、连接器和历史事故生成统一证据记录。
     *
     * <p>{@code retrievedAt} 表示本次诊断取得事实的时间，{@code sourceObservedAt}
     * 表示底层记录最后更新时间。每条证据都带确定性的来源级可信度，供 Python 和 Java
     * 在自动恢复前再次校验。</p>
     */
    private List<SyncExecutionDiagnosisResponse.EvidenceRecord> evidenceRecords(
            SyncTask task,
            SyncExecution execution,
            SyncExecutionDiagnosisResponse.RuntimeMetricSummary runtimeMetrics,
            SyncExecutionDiagnosisResponse.ExecutionPolicyComparison policyComparison,
            List<SyncExecutionDiagnosisResponse.ConnectorRuntimeSummary> connectors,
            List<SyncExecutionDiagnosisResponse.KnowledgeCaseSummary> cases) {
        String retrievedAt = OffsetDateTime.now(ZoneOffset.UTC).toString();
        List<SyncExecutionDiagnosisResponse.EvidenceRecord> records = new ArrayList<>();
        records.add(evidence(
                "STRUCTURED_API",
                "sync-execution:" + task.getId() + ":" + execution.getId() + ":metrics",
                retrievedAt,
                execution.getUpdateTime() == null ? null : execution.getUpdateTime().toString(),
                0.98d,
                "PERSISTED_EXECUTION_AND_OBJECT_LEDGER"));
        if (policyComparison.current() != null) {
            records.add(evidence("STRUCTURED_API",
                    "sync-policy-snapshot:" + policyComparison.current().executionId(), retrievedAt,
                    observedAt(policyComparison.current().capturedAt()), 0.95d,
                    "PERSISTED_EXECUTION_POLICY_SNAPSHOT"));
        }
        if (policyComparison.previousSuccessful() != null) {
            records.add(evidence("CASE_HISTORY",
                    "sync-policy-snapshot:" + policyComparison.previousSuccessful().executionId(), retrievedAt,
                    observedAt(policyComparison.previousSuccessful().capturedAt()), 0.93d,
                    "PREVIOUS_SUCCESSFUL_EXECUTION_POLICY"));
        }
        for (SyncExecutionDiagnosisResponse.ConnectorRuntimeSummary connector : connectors) {
            records.add(evidence("STRUCTURED_API",
                    "datasource-capability:" + connector.connectorRole() + ":" + connector.datasourceId(),
                    retrievedAt, observedAt(connector.generatedAt()),
                    "AVAILABLE".equals(connector.lookupStatus()) ? 0.92d : 0.70d,
                    "LOW_SENSITIVE_CAPABILITY_SNAPSHOT"));
        }
        for (SyncExecutionDiagnosisResponse.KnowledgeCaseSummary item : cases) {
            records.add(evidence("CASE_HISTORY", "sync-incident:" + item.caseId(), retrievedAt,
                    observedAt(item.closedAt()), 0.85d, "RESOLVED_INCIDENT_RECORD"));
        }
        return List.copyOf(records);
    }

    /** 创建一条不含正文、SQL 或对象定位符的稳定证据记录。 */
    private SyncExecutionDiagnosisResponse.EvidenceRecord evidence(
            String sourceType,
            String sourceRef,
            String retrievedAt,
            String sourceObservedAt,
            double confidence,
            String confidenceBasis) {
        return new SyncExecutionDiagnosisResponse.EvidenceRecord(
                "sync-evidence:" + sha256(sourceType + "|" + sourceRef),
                sourceType, sourceRef, retrievedAt, sourceObservedAt, confidence, confidenceBasis);
    }

    /** 将可选本地时间转换为低敏源事实时间文本。 */
    private String observedAt(java.time.LocalDateTime value) {
        return value == null ? null : value.toString();
    }

    private SyncExecution loadExecution(SyncTask task, Long requestedExecutionId) {
        Long executionId = requestedExecutionId != null ? requestedExecutionId : task.getLastExecutionId();
        SyncExecution execution = executionId == null ? null : executionMapper.selectById(executionId);
        if (execution == null) {
            execution = executionMapper.selectOne(new LambdaQueryWrapper<SyncExecution>()
                    .eq(SyncExecution::getSyncTaskId, task.getId())
                    .orderByDesc(SyncExecution::getId)
                    .last("LIMIT 1"));
        }
        if (execution == null) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND,
                    "同步任务尚无可诊断的 execution，taskId=" + task.getId());
        }
        if (!Objects.equals(task.getId(), execution.getSyncTaskId())) {
            throw new PlatformBusinessException(PlatformErrorCode.TENANT_SCOPE_DENIED,
                    "execution 不属于当前同步任务");
        }
        return execution;
    }

    private List<SyncExecutionDiagnosisResponse.ErrorSummary> aggregateErrors(
            List<SyncObjectExecution> objects,
            List<SyncErrorSample> samples) {
        Map<ErrorKey, Long> counts = new LinkedHashMap<>();
        for (SyncObjectExecution item : objects) {
            ErrorKey key = new ErrorKey(code(item.getLastErrorType(), "OBJECT_EXECUTION_ERROR"),
                    code(item.getLastErrorCode(), "UNCLASSIFIED"), safeMessage(item.getLastErrorMessage()), true);
            counts.merge(key, 1L, Long::sum);
        }
        for (SyncErrorSample item : samples) {
            ErrorKey key = new ErrorKey(code(item.getErrorType(), "DIRTY_RECORD"),
                    code(item.getErrorCode(), "UNCLASSIFIED"), safeMessage(item.getErrorMessage()),
                    Boolean.TRUE.equals(item.getRetryable()));
            counts.merge(key, 1L, Long::sum);
        }
        return counts.entrySet().stream().limit(20)
                .map(entry -> new SyncExecutionDiagnosisResponse.ErrorSummary(
                        entry.getKey().type(), entry.getKey().code(), entry.getKey().message(),
                        entry.getValue(), entry.getKey().retryable()))
                .toList();
    }

    private List<String> classify(List<SyncExecutionDiagnosisResponse.ErrorSummary> errors,
                                  SyncExecution execution) {
        LinkedHashSet<String> causes = new LinkedHashSet<>();
        for (SyncExecutionDiagnosisResponse.ErrorSummary error : errors) {
            String text = (error.errorType() + " " + error.errorCode() + " " + error.message())
                    .toUpperCase(Locale.ROOT);
            if (containsAny(text, "23505", "1062", "DUPLICATE", "UNIQUE CONSTRAINT")) {
                causes.add("TARGET_DUPLICATE_KEY");
            }
            if (containsAny(text, "23502", "NOT NULL", "CANNOT BE NULL")) {
                causes.add("TARGET_NOT_NULL_VIOLATION");
            }
            if (containsAny(text, "23503", "1452", "FOREIGN KEY", "REFERENTIAL INTEGRITY")) {
                causes.add("TARGET_FOREIGN_KEY_VIOLATION");
            }
            if (containsAny(text, "42703", "UNKNOWN COLUMN", "COLUMN NOT FOUND", "COLUMN DOES NOT EXIST")) {
                causes.add("SCHEMA_COLUMN_MISMATCH");
            }
            if (containsAny(text, "22001", "DATA TOO LONG", "VALUE TOO LONG", "TRUNCATION")) {
                causes.add("TARGET_COLUMN_TOO_NARROW");
            }
            if (containsAny(text, "CONVERSION", "INVALID DATE", "INVALID INPUT SYNTAX", "FORMAT")) {
                causes.add("TYPE_OR_FORMAT_CONVERSION_FAILED");
            }
            if (containsAny(text, "CONNECTION", "TIMEOUT", "COMMUNICATION", "UNAVAILABLE")) {
                causes.add("CONNECTOR_OR_NETWORK_UNAVAILABLE");
            }
            if (containsAny(text, "PERMISSION", "DENIED", "AUTHORIZATION")) {
                causes.add("DATASOURCE_PERMISSION_DENIED");
            }
        }
        if (causes.isEmpty() && "FAILED".equalsIgnoreCase(execution.getExecutionState())) {
            causes.add("UNCLASSIFIED_EXECUTION_FAILURE");
        }
        return List.copyOf(causes);
    }

    private List<String> repairActions(List<String> causes,
                                       List<SyncObjectExecution> failedObjects,
                                       List<SyncErrorSample> samples) {
        LinkedHashSet<String> actions = new LinkedHashSet<>();
        if (!failedObjects.isEmpty()) {
            actions.add("RETRY_FAILED_OBJECTS_AFTER_ROOT_CAUSE_FIXED");
        }
        if (causes.contains("TARGET_COLUMN_TOO_NARROW")) {
            actions.add("PREVIEW_TARGET_VARCHAR_WIDEN");
        }
        if (causes.contains("TARGET_NOT_NULL_VIOLATION")) {
            actions.add("REPAIR_FIELD_MAPPING");
            actions.add("PREVIEW_TARGET_DROP_NOT_NULL_OR_FIX_SOURCE_VALUE");
        }
        if (causes.contains("SCHEMA_COLUMN_MISMATCH")) {
            actions.add("REPAIR_FIELD_MAPPING");
            actions.add("PREVIEW_TARGET_ADD_NULLABLE_COLUMN_OR_REPAIR_FIELD_MAPPING");
        }
        if (causes.contains("TARGET_FOREIGN_KEY_VIOLATION")) {
            actions.add("REVIEW_PARENT_DEPENDENCY_OR_REPAIR_SOURCE_REFERENCE");
        }
        if (causes.contains("TARGET_DUPLICATE_KEY")) {
            actions.add("REVIEW_WRITE_STRATEGY_OR_QUARANTINE_DUPLICATE_RECORD");
        }
        if (samples.stream().anyMatch(item -> Boolean.TRUE.equals(item.getRetryable()))) {
            actions.add("PREVIEW_DIRTY_RECORD_QUARANTINE");
            actions.add("REPLAY_DIRTY_RECORD_AFTER_FIX");
        }
        if (actions.isEmpty()) {
            actions.add("REVIEW_EXECUTION_LOG_AND_CONNECTOR_HEALTH");
        }
        return List.copyOf(actions);
    }

    private List<SyncExecutionDiagnosisResponse.KnowledgeCaseSummary> similarCases(
            SyncTask task,
            List<String> rootCauses) {
        LambdaQueryWrapper<SyncIncidentRecord> wrapper = new LambdaQueryWrapper<SyncIncidentRecord>()
                .eq(SyncIncidentRecord::getTenantId, task.getTenantId())
                .in(SyncIncidentRecord::getIncidentStatus, List.of("RESOLVED", "CLOSED"))
                .orderByDesc(SyncIncidentRecord::getClosedAt)
                .orderByDesc(SyncIncidentRecord::getId)
                .last("LIMIT 20");
        if (task.getProjectId() == null) {
            wrapper.isNull(SyncIncidentRecord::getProjectId);
        } else {
            wrapper.eq(SyncIncidentRecord::getProjectId, task.getProjectId());
        }
        List<SyncIncidentRecord> records = incidentRecordMapper.selectList(wrapper);
        if (records == null) {
            return List.of();
        }
        return records.stream()
                .filter(item -> rootCauses.isEmpty() || rootCauses.stream().anyMatch(cause ->
                        containsIgnoreCase(item.getIncidentType(), cause)
                                || containsIgnoreCase(item.getTitle(), cause)
                                || containsIgnoreCase(item.getDescription(), cause)))
                .limit(MAX_CASES)
                .map(item -> new SyncExecutionDiagnosisResponse.KnowledgeCaseSummary(
                        item.getId(), item.getIncidentType(), truncate(item.getTitle(), 160),
                        truncate(item.getResolutionSummary(), 500), item.getClosedAt()))
                .toList();
    }

    private String ragQuery(SyncTaskDefinition definition,
                            List<String> causes,
                            List<SyncExecutionDiagnosisResponse.ErrorSummary> errors) {
        String codes = errors.stream().map(SyncExecutionDiagnosisResponse.ErrorSummary::errorCode)
                .filter(value -> value != null && !value.isBlank()).distinct().limit(8)
                .reduce((left, right) -> left + "," + right).orElse("UNCLASSIFIED");
        return "DataSmart 数据同步失败排查：源连接器=" + code(definition.getSourceConnectorType(), "UNKNOWN")
                + "，目标连接器=" + code(definition.getTargetConnectorType(), "UNKNOWN")
                + "，同步模式=" + code(definition.getSyncMode(), "UNKNOWN")
                + "，写入策略=" + code(definition.getWriteStrategy(), "UNKNOWN")
                + "，根因分类=" + String.join(",", causes)
                + "，错误码=" + codes
                + "。检索安全修复步骤、类似事故案例、验证与回滚方法。";
    }

    private SyncExecutionDiagnosisResponse.FailedObjectSummary failedObjectSummary(SyncObjectExecution item) {
        return new SyncExecutionDiagnosisResponse.FailedObjectSummary(
                item.getId(), item.getObjectOrdinal(), item.getWorkUnitType(), item.getShardOrPartition(),
                item.getTargetSchemaName(), item.getTargetObjectName(), item.getLastErrorType(),
                item.getLastErrorCode(), safeMessage(item.getLastErrorMessage()));
    }

    private boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsIgnoreCase(String value, String expected) {
        return value != null && expected != null
                && value.toUpperCase(Locale.ROOT).contains(expected.toUpperCase(Locale.ROOT));
    }

    private String safeMessage(String value) {
        String normalized = truncate(value == null ? "未提供低敏错误摘要" : value.trim(), 300);
        String lower = normalized.toLowerCase(Locale.ROOT);
        return SENSITIVE_MARKERS.stream().anyMatch(lower::contains)
                ? "执行细节已隐藏，请结合结构化错误码和受控日志诊断"
                : normalized;
    }

    private String code(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toUpperCase(Locale.ROOT);
    }

    private String truncate(String value, int length) {
        return value == null || value.length() <= length ? value : value.substring(0, length);
    }

    private long zero(Long value) {
        return value == null ? 0L : value;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 缺少 SHA-256", exception);
        }
    }

    private record ErrorKey(String type, String code, String message, boolean retryable) {
    }
}
