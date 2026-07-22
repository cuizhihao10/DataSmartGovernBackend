package com.czh.datasmart.govern.datasync.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncRecoveryCasePublishRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncRecoveryCasePublishResult;
import com.czh.datasmart.govern.datasync.entity.SyncAuditRecord;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncIncidentRecord;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.mapper.SyncAuditRecordMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncIncidentRecordMapper;
import com.czh.datasmart.govern.datasync.support.SyncAuditActionType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Publishes only recovery cases proven by a later successful execution. */
@Component
@RequiredArgsConstructor
public class SyncRecoveryCasePublishSupport {

    private static final String CASE_TYPE = "AGENT_RECOVERY_CASE";
    private static final Pattern SAFE_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,63}$");
    private static final Pattern SAFE_REFERENCE = Pattern.compile("^[A-Za-z0-9._:/-]{1,128}$");

    private final SyncExecutionMapper executionMapper;
    private final SyncAuditRecordMapper auditRecordMapper;
    private final SyncIncidentRecordMapper incidentMapper;
    private final SyncAuditSupport auditSupport;

    public SyncRecoveryCasePublishResult publish(SyncTask task,
                                                 SyncRecoveryCasePublishRequest request,
                                                 SyncActorContext actorContext) {
        if (request == null || request.getDiagnosisExecutionId() == null
                || request.getValidationExecutionId() == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "发布恢复案例必须提供诊断执行和验证执行");
        }
        SyncExecution diagnosis = requiredExecution(task, request.getDiagnosisExecutionId());
        SyncExecution validation = requiredExecution(task, request.getValidationExecutionId());
        boolean diagnosisFailureProvenByCurrentState = List.of("FAILED", "PARTIALLY_SUCCEEDED")
                .contains(diagnosis.getExecutionState());
        boolean diagnosisFailureProvenByRetryAudit = hasObjectRetryFailureEvidence(task, diagnosis);
        if (!diagnosisFailureProvenByCurrentState && !diagnosisFailureProvenByRetryAudit) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "诊断执行没有失败/部分成功状态或失败对象重试审计证据，不能发布恢复案例");
        }
        if (!"SUCCEEDED".equals(validation.getExecutionState())
                || value(validation.getFailedRecordCount()) != 0L) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "修复后的验证执行尚未成功，不能发布恢复案例");
        }

        List<String> rootCauses = normalizeCodes(request.getRootCauseCodes(), "rootCauseCodes");
        List<String> actions = normalizeCodes(request.getRepairActionCodes(), "repairActionCodes");
        List<String> references = normalizeReferences(request.getEvidenceReferences());
        if (rootCauses.isEmpty() || actions.isEmpty()) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "恢复案例至少需要一个根因码和一个修复动作码");
        }

        SyncIncidentRecord existing = incidentMapper.selectOne(new LambdaQueryWrapper<SyncIncidentRecord>()
                .eq(SyncIncidentRecord::getTenantId, task.getTenantId())
                .eq(SyncIncidentRecord::getProjectId, task.getProjectId())
                .eq(SyncIncidentRecord::getSyncTaskId, task.getId())
                .eq(SyncIncidentRecord::getExecutionId, validation.getId())
                .eq(SyncIncidentRecord::getIncidentType, CASE_TYPE)
                .last("LIMIT 1"));
        if (existing != null) {
            return result(existing.getId(), task, diagnosis, validation, rootCauses, actions, true);
        }

        LocalDateTime now = LocalDateTime.now();
        String evidence = references.isEmpty() ? "none" : String.join(",", references);
        String resolution = truncate("rootCauseCodes=" + String.join(",", rootCauses)
                + ";repairActionCodes=" + String.join(",", actions)
                + ";evidenceRefs=" + evidence
                + ";diagnosisEvidence="
                + (diagnosisFailureProvenByCurrentState ? "FAILED_STATE" : "OBJECT_RETRY_AUDIT")
                + ";validationState=SUCCEEDED"
                + ";recordsRead=" + value(validation.getRecordsRead())
                + ";recordsWritten=" + value(validation.getRecordsWritten())
                + ";failedRecords=0", 1900);

        SyncIncidentRecord record = new SyncIncidentRecord();
        record.setTenantId(task.getTenantId());
        record.setProjectId(task.getProjectId());
        record.setWorkspaceId(null);
        record.setSyncTaskId(task.getId());
        record.setExecutionId(validation.getId());
        record.setIncidentType(CASE_TYPE);
        record.setSeverity("P4");
        record.setIncidentStatus("CLOSED");
        record.setTitle(truncate("Verified Agent recovery: " + String.join(",", rootCauses), 250));
        record.setDescription(resolution);
        record.setResolutionSummary(resolution);
        record.setOperatorId(actorContext == null ? null : actorContext.actorId());
        record.setOperatorRole(actorContext == null ? null : actorContext.actorRole());
        record.setResolvedAt(now);
        record.setClosedAt(now);
        record.setCreateTime(now);
        record.setUpdateTime(now);
        incidentMapper.insert(record);
        auditSupport.saveAudit(task.getTenantId(), task.getId(), validation.getId(),
                SyncAuditActionType.PUBLISH_RECOVERY_CASE, actorContext,
                "caseId=" + record.getId() + ",diagnosisExecutionId=" + diagnosis.getId()
                        + ",rootCauseCount=" + rootCauses.size() + ",repairActionCount=" + actions.size());
        return result(record.getId(), task, diagnosis, validation, rootCauses, actions, false);
    }

    /**
     * 对象级选择性重试会复用父 execution：它在重试入口必须是 FAILED/PARTIALLY_SUCCEEDED，
     * 但 worker 成功后同一行会变成 SUCCEEDED。此时不能再依赖可变的当前状态证明历史失败，
     * 而应读取重试事务留下的审计记录。该动作只有在服务端状态前置校验通过后才会写入，
     * 因而可以作为“不接受模型或客户端自报失败”的持久化证据。
     */
    private boolean hasObjectRetryFailureEvidence(SyncTask task, SyncExecution diagnosis) {
        Long count = auditRecordMapper.selectCount(new LambdaQueryWrapper<SyncAuditRecord>()
                .eq(SyncAuditRecord::getTenantId, task.getTenantId())
                .eq(SyncAuditRecord::getProjectId, task.getProjectId())
                .eq(SyncAuditRecord::getSyncTaskId, task.getId())
                .eq(SyncAuditRecord::getExecutionId, diagnosis.getId())
                .eq(SyncAuditRecord::getActionType, SyncAuditActionType.RETRY_OBJECT_EXECUTIONS.name()));
        return count != null && count > 0L;
    }

    private SyncExecution requiredExecution(SyncTask task, Long executionId) {
        SyncExecution execution = executionMapper.selectById(executionId);
        if (execution == null || !task.getId().equals(execution.getSyncTaskId())
                || !task.getTenantId().equals(execution.getTenantId())) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND,
                    "执行记录不存在或不属于当前任务: " + executionId);
        }
        return execution;
    }

    private List<String> normalizeCodes(List<String> values, String field) {
        if (values == null) {
            return List.of();
        }
        List<String> normalized = values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .distinct()
                .limit(20)
                .toList();
        if (normalized.stream().anyMatch(value -> !SAFE_CODE.matcher(value).matches())) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    field + " 只能包含稳定的低敏编码");
        }
        return normalized;
    }

    private List<String> normalizeReferences(List<String> values) {
        if (values == null) {
            return List.of();
        }
        List<String> normalized = values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .limit(10)
                .toList();
        if (normalized.stream().anyMatch(value -> !SAFE_REFERENCE.matcher(value).matches())) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "evidenceReferences 只能包含不带查询参数的低敏引用标识");
        }
        return normalized;
    }

    private SyncRecoveryCasePublishResult result(Long caseId,
                                                 SyncTask task,
                                                 SyncExecution diagnosis,
                                                 SyncExecution validation,
                                                 List<String> rootCauses,
                                                 List<String> actions,
                                                 boolean reused) {
        return SyncRecoveryCasePublishResult.builder()
                .caseId(caseId)
                .syncTaskId(task.getId())
                .diagnosisExecutionId(diagnosis.getId())
                .validationExecutionId(validation.getId())
                .caseStatus("CLOSED")
                .rootCauseCodes(rootCauses)
                .repairActionCodes(actions)
                .reusedExistingCase(reused)
                .build();
    }

    private long value(Long value) {
        return value == null ? 0L : value;
    }

    private String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
