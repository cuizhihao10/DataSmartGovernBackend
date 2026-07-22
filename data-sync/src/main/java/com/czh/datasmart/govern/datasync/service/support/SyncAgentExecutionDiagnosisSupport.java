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
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionDiagnosisResponse;
import com.czh.datasmart.govern.datasync.entity.SyncErrorSample;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncIncidentRecord;
import com.czh.datasmart.govern.datasync.entity.SyncObjectExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.entity.SyncTemplate;
import com.czh.datasmart.govern.datasync.mapper.SyncErrorSampleMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncIncidentRecordMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncObjectExecutionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
 * Aggregates real execution facts into a bounded diagnosis package for the Agent.
 *
 * <p>This is deliberately deterministic: the model reasons over the resulting cause codes,
 * but it cannot invent task state, failed-shard counts or retryable dirty-record counts.</p>
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

    public SyncExecutionDiagnosisResponse diagnose(SyncTask task, SyncTemplate template, Long requestedExecutionId) {
        SyncExecution execution = loadExecution(task, requestedExecutionId);
        List<SyncObjectExecution> allObjects = objectExecutionMapper.selectByExecutionId(execution.getId());
        List<SyncObjectExecution> failedObjects = allObjects == null ? List.of() : allObjects.stream()
                .filter(item -> "FAILED".equalsIgnoreCase(item.getObjectState()))
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
        String ragQuery = ragQuery(template, rootCauses, errors);
        String digest = sha256(task.getId() + "|" + execution.getId() + "|"
                + String.join(",", rootCauses) + "|" + String.join(",", repairActions));

        return new SyncExecutionDiagnosisResponse(
                task.getId(), template.getId(), execution.getId(), task.getCurrentState(),
                execution.getExecutionState(), template.getSyncMode(), template.getWriteStrategy(),
                template.getSourceConnectorType(), template.getTargetConnectorType(), template.getTargetDatasourceId(),
                zero(execution.getRecordsRead()), zero(execution.getRecordsWritten()),
                zero(execution.getFailedRecordCount()), failedObjects.size(),
                (int) samples.stream().filter(item -> Boolean.TRUE.equals(item.getRetryable()))
                        .filter(item -> !"QUARANTINED".equalsIgnoreCase(item.getResolutionStatus())).count(),
                (int) samples.stream().filter(item -> "QUARANTINED".equalsIgnoreCase(item.getResolutionStatus())).count(),
                failedObjects.stream().map(this::failedObjectSummary).toList(),
                errors, rootCauses, repairActions, cases, ragQuery, digest,
                "LOW_SENSITIVE_DIAGNOSIS_NO_SQL_NO_CREDENTIALS_NO_SOURCE_KEYS_NO_SAMPLE_PAYLOAD"
        );
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
            actions.add("PREVIEW_TARGET_DROP_NOT_NULL_OR_FIX_SOURCE_VALUE");
        }
        if (causes.contains("SCHEMA_COLUMN_MISMATCH")) {
            actions.add("PREVIEW_TARGET_ADD_NULLABLE_COLUMN_OR_REPAIR_FIELD_MAPPING");
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
                        truncate(item.getResolutionSummary(), 500)))
                .toList();
    }

    private String ragQuery(SyncTemplate template,
                            List<String> causes,
                            List<SyncExecutionDiagnosisResponse.ErrorSummary> errors) {
        String codes = errors.stream().map(SyncExecutionDiagnosisResponse.ErrorSummary::errorCode)
                .filter(value -> value != null && !value.isBlank()).distinct().limit(8)
                .reduce((left, right) -> left + "," + right).orElse("UNCLASSIFIED");
        return "DataSmart 数据同步失败排查：源连接器=" + code(template.getSourceConnectorType(), "UNKNOWN")
                + "，目标连接器=" + code(template.getTargetConnectorType(), "UNKNOWN")
                + "，同步模式=" + code(template.getSyncMode(), "UNKNOWN")
                + "，写入策略=" + code(template.getWriteStrategy(), "UNKNOWN")
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
