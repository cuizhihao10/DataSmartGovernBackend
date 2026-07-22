/**
 * @Author : Cui
 * @Date: 2026/07/22
 * @Description DataSmart Govern Backend - SyncDirtyRecordQuarantineSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncDirtyRecordQuarantineRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncDirtyRecordQuarantineResult;
import com.czh.datasmart.govern.datasync.entity.SyncErrorSample;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.mapper.SyncErrorSampleMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionMapper;
import com.czh.datasmart.govern.datasync.support.SyncAuditActionType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Creates and applies a reversible dirty-row quarantine decision.
 *
 * <p>The model never receives or edits source keys. This component validates the persisted
 * {@code PRIMARY_KEY_EQ} selectors, binds the preview to a digest and stores only a state
 * transition on the error sample. The source database is not modified.</p>
 */
@Component
@RequiredArgsConstructor
public class SyncDirtyRecordQuarantineSupport {

    private static final int MAX_SAMPLE_COUNT = 500;
    private static final String OPEN = "OPEN";
    private static final String QUARANTINED = "QUARANTINED";

    private final SyncErrorSampleMapper errorSampleMapper;
    private final SyncExecutionMapper executionMapper;
    private final SyncAuditSupport auditSupport;
    private final ObjectMapper objectMapper;

    public SyncDirtyRecordQuarantineResult preview(SyncTask task, SyncDirtyRecordQuarantineRequest request) {
        Selection selection = select(task, request);
        List<String> issues = new ArrayList<>();
        if (selection.eligible().size() != selection.selected().size()) {
            issues.add("DIRTY_SAMPLE_SELECTOR_NOT_PRIMARY_KEY_EQ");
        }
        if (selection.eligible().isEmpty()) {
            issues.add("NO_ELIGIBLE_DIRTY_SAMPLE");
        }
        String digest = confirmationDigest(task, selection.execution(), selection.eligible(), request.getReason());
        return result(task, selection, 0, "PREVIEWED", digest, issues,
                issues.isEmpty()
                        ? "隔离预览已生成；确认后这些记录会在失败对象重试时被精确跳过，源数据不会被删除。"
                        : "部分错误样本缺少可验证的 PRIMARY_KEY_EQ 定位，不能自动隔离。"
        );
    }

    @Transactional
    public SyncDirtyRecordQuarantineResult apply(SyncTask task,
                                                 SyncDirtyRecordQuarantineRequest request,
                                                 SyncActorContext actorContext) {
        if (!Boolean.TRUE.equals(request.getConfirmed())) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "应用脏数据隔离必须显式确认 confirmed=true");
        }
        Selection selection = select(task, request);
        if (selection.eligible().isEmpty() || selection.eligible().size() != selection.selected().size()) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "所选错误样本并非全部具备 PRIMARY_KEY_EQ 精确定位，已拒绝自动隔离");
        }
        String expectedDigest = confirmationDigest(task, selection.execution(), selection.eligible(), request.getReason());
        if (!Objects.equals(expectedDigest, normalize(request.getConfirmationDigest()))) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "隔离确认摘要与最新预览不一致，请重新预览后确认");
        }

        int affected = 0;
        Long actorId = actorContext == null ? null : actorContext.actorId();
        String noteDigest = sha256(normalize(request.getReason()));
        for (SyncErrorSample sample : selection.eligible()) {
            affected += errorSampleMapper.update(null, new LambdaUpdateWrapper<SyncErrorSample>()
                    .eq(SyncErrorSample::getId, sample.getId())
                    .eq(SyncErrorSample::getResolutionStatus, OPEN)
                    .set(SyncErrorSample::getResolutionStatus, QUARANTINED)
                    .set(SyncErrorSample::getResolutionAction, "QUARANTINE_FOR_RETRY")
                    .set(SyncErrorSample::getResolutionNoteDigest, noteDigest)
                    .set(SyncErrorSample::getResolvedBy, actorId)
                    .set(SyncErrorSample::getResolvedAt, LocalDateTime.now()));
        }
        if (affected != selection.eligible().size()) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "部分错误样本状态已变化，请重新诊断和预览后再确认");
        }
        auditSupport.saveAudit(task.getTenantId(), task.getId(), selection.execution().getId(),
                SyncAuditActionType.QUARANTINE_DIRTY_RECORDS, actorContext,
                "selectedCount=" + affected + ",confirmationDigest=" + expectedDigest);
        return result(task, selection, affected, "APPLIED", expectedDigest, List.of(),
                "脏数据已进入隔离账本；后续失败对象重试会跳过这些精确主键记录。"
        );
    }

    private Selection select(SyncTask task, SyncDirtyRecordQuarantineRequest request) {
        if (request == null || request.getExecutionId() == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST, "必须指定来源 executionId");
        }
        SyncExecution execution = executionMapper.selectById(request.getExecutionId());
        if (execution == null || !Objects.equals(task.getId(), execution.getSyncTaskId())) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND, "来源 execution 不属于当前同步任务");
        }
        List<Long> requestedIds = normalizeIds(request.getErrorSampleIds());
        boolean selectAll = Boolean.TRUE.equals(request.getQuarantineAllRetryableInExecution());
        if (requestedIds.isEmpty() == !selectAll) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "必须二选一：提供 errorSampleIds，或设置 quarantineAllRetryableInExecution=true");
        }
        LambdaQueryWrapper<SyncErrorSample> wrapper = new LambdaQueryWrapper<SyncErrorSample>()
                .eq(SyncErrorSample::getSyncTaskId, task.getId())
                .eq(SyncErrorSample::getExecutionId, execution.getId())
                .eq(SyncErrorSample::getTenantId, task.getTenantId())
                .eq(SyncErrorSample::getRetryable, true)
                .eq(SyncErrorSample::getResolutionStatus, OPEN)
                .orderByAsc(SyncErrorSample::getId);
        if (selectAll) {
            wrapper.last("LIMIT " + MAX_SAMPLE_COUNT);
        } else {
            wrapper.in(SyncErrorSample::getId, requestedIds);
        }
        List<SyncErrorSample> selected = errorSampleMapper.selectList(wrapper);
        selected = selected == null ? List.of() : selected;
        if (!selectAll && selected.size() != requestedIds.size()) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "部分错误样本不存在、不可重试、已处理或不属于当前 execution");
        }
        List<SyncErrorSample> eligible = selected.stream().filter(this::hasPrimaryKeySelector).toList();
        return new Selection(execution, selected, eligible);
    }

    private boolean hasPrimaryKeySelector(SyncErrorSample sample) {
        try {
            JsonNode root = objectMapper.readTree(sample.getSourceRecordKey());
            return root != null
                    && "PRIMARY_KEY_EQ".equalsIgnoreCase(root.path("strategy").asText())
                    && !root.path("column").asText().isBlank()
                    && root.has("value") && !root.get("value").isNull();
        } catch (Exception ignored) {
            return false;
        }
    }

    private SyncDirtyRecordQuarantineResult result(SyncTask task,
                                                    Selection selection,
                                                    int affected,
                                                    String state,
                                                    String digest,
                                                    List<String> issues,
                                                    String message) {
        return new SyncDirtyRecordQuarantineResult(
                task.getId(), selection.execution().getId(), selection.selected().size(),
                selection.eligible().size(), affected, state, digest,
                selection.eligible().stream().map(SyncErrorSample::getId).toList(),
                List.copyOf(issues), message);
    }

    private String confirmationDigest(SyncTask task,
                                      SyncExecution execution,
                                      List<SyncErrorSample> samples,
                                      String reason) {
        String ids = samples.stream().map(SyncErrorSample::getId).sorted()
                .map(String::valueOf).reduce((left, right) -> left + "," + right).orElse("");
        return sha256("v1|" + task.getTenantId() + "|" + task.getProjectId() + "|" + task.getId()
                + "|" + execution.getId() + "|" + ids + "|" + normalize(reason));
    }

    private List<Long> normalizeIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<Long> normalized = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id == null || id <= 0) {
                throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                        "errorSampleIds 只能包含正整数");
            }
            normalized.add(id);
        }
        if (normalized.size() > MAX_SAMPLE_COUNT) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "单次最多隔离 " + MAX_SAMPLE_COUNT + " 条错误样本");
        }
        return List.copyOf(normalized);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(normalize(value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 缺少 SHA-256", exception);
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private record Selection(SyncExecution execution,
                             List<SyncErrorSample> selected,
                             List<SyncErrorSample> eligible) {
    }
}
