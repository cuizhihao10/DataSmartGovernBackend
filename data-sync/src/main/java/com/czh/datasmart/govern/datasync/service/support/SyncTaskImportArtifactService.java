/**
 * @Author : Cui
 * @Date: 2026/07/22 18:40
 * @Description DataSmart Govern Backend - SyncTaskImportArtifactService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskImportArtifactCommitResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskImportArtifactDryRunResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskImportArtifactView;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskImportCellPatch;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskImportCommitRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskImportOptions;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskImportRepairRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskImportResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskImportRowResult;
import com.czh.datasmart.govern.datasync.entity.SyncTaskImportArtifact;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskImportArtifactMapper;
import com.czh.datasmart.govern.datasync.service.DataSyncService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Owns the immutable artifact -> dry-run -> repair -> confirmed import lifecycle.
 *
 * <p>Files are bounded to 10 MiB because this implementation stores the body in
 * PostgreSQL. The stable artifact reference deliberately hides that storage choice;
 * a MinIO body adapter can replace it later without changing Agent tools or UI APIs.</p>
 */
@Service
@RequiredArgsConstructor
public class SyncTaskImportArtifactService {

    private static final int MAX_ARTIFACT_BYTES = 10 * 1024 * 1024;
    private static final int MAX_PATCHES = 200;

    private final SyncTaskImportArtifactMapper artifactMapper;
    private final SyncTaskDefinitionExchangeCodecSupport codecSupport;
    private final DataSyncService dataSyncService;
    private final ObjectMapper objectMapper;

    /** Upload and validate an immutable source artifact without importing tasks. */
    @Transactional
    public SyncTaskImportArtifactView upload(byte[] content,
                                             String fileName,
                                             String requestedFormat,
                                             SyncActorContext actorContext) {
        requireActorScope(actorContext);
        if (content == null || content.length == 0 || content.length > MAX_ARTIFACT_BYTES) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "任务导入制品必须大于 0 且不超过 10 MiB");
        }
        String format = codecSupport.resolveFormat(requestedFormat, fileName);
        codecSupport.decodeRows(content, format);
        SyncTaskImportArtifact artifact = newArtifact(
                content, safeFileName(fileName, format), format, actorContext,
                null, 1, "UPLOADED", null);
        artifactMapper.insert(artifact);
        return view(artifact);
    }

    /** Return metadata only after revalidating owner and project boundaries. */
    public SyncTaskImportArtifactView detail(String artifactRef, SyncActorContext actorContext) {
        return view(requireOwnedArtifact(artifactRef, actorContext));
    }

    /** Execute the real import validator and persist only structured diagnostics plus a digest. */
    @Transactional
    public SyncTaskImportArtifactDryRunResult dryRun(String artifactRef,
                                                     boolean runImmediately,
                                                     SyncActorContext actorContext) {
        SyncTaskImportArtifact artifact = requireOwnedArtifact(artifactRef, actorContext);
        if ("COMMITTED".equals(artifact.getArtifactState())) {
            throw stateConflict("已提交的导入制品不能再次试运行，请重新上传新版本");
        }
        SyncTaskImportOptions options = options(artifact, true, runImmediately);
        SyncTaskImportResult result = dataSyncService.importTasks(artifact.getContentBody(), options, actorContext);
        structureDiagnostics(result);
        String digest = digest(artifact.getContentHash() + "|" + runImmediately + "|" + json(result));
        artifact.setDryRunStatus(result.getStatus());
        artifact.setDryRunDigest(digest);
        artifact.setDiagnosticsJson(json(result));
        artifact.setArtifactState(isValidated(result) ? "VALIDATED" : "DRY_RUN_FAILED");
        artifact.setUpdateTime(LocalDateTime.now());
        artifactMapper.updateById(artifact);
        return new SyncTaskImportArtifactDryRunResult(
                view(artifact), result, digest, ragQuery(result), !isValidated(result));
    }

    /**
     * Apply a user-confirmed model proposal to a new immutable artifact version.
     * The confirmation digest binds the patch to the exact dry-run the user reviewed.
     */
    @Transactional
    public SyncTaskImportArtifactView applyRepair(String artifactRef,
                                                  SyncTaskImportRepairRequest request,
                                                  SyncActorContext actorContext) {
        SyncTaskImportArtifact base = requireOwnedArtifact(artifactRef, actorContext);
        if (request == null || request.patches() == null || request.patches().isEmpty()
                || request.patches().size() > MAX_PATCHES) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "修复补丁数量必须在 1 到 " + MAX_PATCHES + " 之间");
        }
        if (!request.baseVersion().equals(base.getVersionNumber())) {
            throw stateConflict("修复建议对应的制品版本已过期，请重新试运行后确认");
        }
        requireDigest(base.getDryRunDigest(), request.confirmationDigest(), "修复确认");
        List<SyncTaskDefinitionExchangeCodecSupport.CellRepair> repairs = request.patches().stream()
                .map(this::toCodecRepair)
                .toList();
        byte[] repairedContent = codecSupport.applyRepairs(base.getContentBody(), base.getFileFormat(), repairs);
        String patchDigest = digest(json(request.patches()));
        SyncTaskImportArtifact repaired = newArtifact(
                repairedContent,
                versionedFileName(base.getFileName(), base.getVersionNumber() + 1),
                base.getFileFormat(),
                actorContext,
                base.getId(),
                base.getVersionNumber() + 1,
                "REPAIRED",
                patchDigest);
        artifactMapper.insert(repaired);
        return view(repaired);
    }

    /** Import only an artifact whose latest dry-run passed and whose digest the user confirmed. */
    @Transactional
    public SyncTaskImportArtifactCommitResult commit(String artifactRef,
                                                     SyncTaskImportCommitRequest request,
                                                     SyncActorContext actorContext) {
        SyncTaskImportArtifact artifact = requireOwnedArtifact(artifactRef, actorContext);
        if ("COMMITTED".equals(artifact.getArtifactState())) {
            throw stateConflict("该制品已经提交过，重复提交会创建重复任务，已被幂等保护阻断");
        }
        if (!"VALIDATED".equals(artifact.getArtifactState()) || !"VALIDATED".equals(artifact.getDryRunStatus())) {
            throw stateConflict("只有最近一次试运行通过的制品才能正式导入");
        }
        requireDigest(artifact.getDryRunDigest(), request.confirmationDigest(), "正式导入确认");
        boolean runImmediately = Boolean.TRUE.equals(request.runImmediately());
        SyncTaskImportResult result = dataSyncService.importTasks(
                artifact.getContentBody(), options(artifact, false, runImmediately), actorContext);
        structureDiagnostics(result);
        if (!"IMPORTED".equals(result.getStatus())) {
            throw stateConflict("正式导入前数据状态已变化，请重新试运行并确认最新诊断");
        }
        artifact.setArtifactState("COMMITTED");
        artifact.setUpdateTime(LocalDateTime.now());
        artifactMapper.updateById(artifact);
        return new SyncTaskImportArtifactCommitResult(view(artifact), result);
    }

    private SyncTaskImportArtifact newArtifact(byte[] content,
                                               String fileName,
                                               String format,
                                               SyncActorContext actorContext,
                                               Long parentArtifactId,
                                               int version,
                                               String state,
                                               String patchDigest) {
        LocalDateTime now = LocalDateTime.now();
        SyncTaskImportArtifact artifact = new SyncTaskImportArtifact();
        // Keep the opaque reference path-safe so Nginx, gateway rewrites and
        // servlet path-variable parsing do not need special handling for ':'.
        artifact.setArtifactRef("sync-import-" + UUID.randomUUID());
        artifact.setTenantId(actorContext.tenantId());
        artifact.setProjectId(actorContext.projectId());
        artifact.setOwnerId(actorContext.actorId());
        artifact.setParentArtifactId(parentArtifactId);
        artifact.setVersionNumber(version);
        artifact.setFileName(fileName);
        artifact.setFileFormat(format);
        artifact.setContentHash(digest(content));
        artifact.setContentBody(content.clone());
        artifact.setContentSizeBytes((long) content.length);
        artifact.setArtifactState(state);
        artifact.setRepairPatchDigest(patchDigest);
        artifact.setCreateTime(now);
        artifact.setUpdateTime(now);
        return artifact;
    }

    private SyncTaskImportArtifact requireOwnedArtifact(String artifactRef, SyncActorContext actorContext) {
        requireActorScope(actorContext);
        if (artifactRef == null || artifactRef.isBlank()) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, "artifactRef 不能为空");
        }
        SyncTaskImportArtifact artifact = artifactMapper.selectOne(
                new LambdaQueryWrapper<SyncTaskImportArtifact>()
                        .eq(SyncTaskImportArtifact::getArtifactRef, artifactRef.trim())
                        .eq(SyncTaskImportArtifact::getTenantId, actorContext.tenantId())
                        .eq(SyncTaskImportArtifact::getProjectId, actorContext.projectId())
                        .eq(SyncTaskImportArtifact::getOwnerId, actorContext.actorId())
                        .last("LIMIT 1"));
        if (artifact == null) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND,
                    "未找到当前用户在本项目中的任务导入制品");
        }
        return artifact;
    }

    private void requireActorScope(SyncActorContext actorContext) {
        if (actorContext == null || actorContext.tenantId() == null
                || actorContext.projectId() == null || actorContext.actorId() == null) {
            throw new PlatformBusinessException(PlatformErrorCode.TENANT_SCOPE_DENIED,
                    "任务导入制品必须在已登录用户当前选择的租户和项目中操作");
        }
    }

    private SyncTaskImportArtifactView view(SyncTaskImportArtifact artifact) {
        String parentRef = null;
        if (artifact.getParentArtifactId() != null) {
            SyncTaskImportArtifact parent = artifactMapper.selectById(artifact.getParentArtifactId());
            parentRef = parent == null ? null : parent.getArtifactRef();
        }
        return SyncTaskImportArtifactView.from(artifact, parentRef);
    }

    private SyncTaskImportOptions options(SyncTaskImportArtifact artifact,
                                          boolean dryRun,
                                          boolean runImmediately) {
        SyncTaskImportOptions options = new SyncTaskImportOptions();
        options.setFileName(artifact.getFileName());
        options.setFormat(artifact.getFileFormat());
        options.setDryRun(dryRun);
        options.setRunImmediately(runImmediately);
        return options;
    }

    private void structureDiagnostics(SyncTaskImportResult result) {
        if (result == null || result.getRows() == null) {
            return;
        }
        result.getRows().forEach(this::structureRowDiagnostic);
    }

    private void structureRowDiagnostic(SyncTaskImportRowResult row) {
        if (row == null || !List.of("FAILED", "CONFLICT").contains(row.getStatus())) {
            return;
        }
        String message = row.getMessage() == null ? "" : row.getMessage();
        String lower = message.toLowerCase(Locale.ROOT);
        if ("CONFLICT".equals(row.getStatus()) || lower.contains("名称重复") || lower.contains("名称与现有任务冲突")) {
            diagnostic(row, "IMPORT_TASK_NAME_CONFLICT", "name", true,
                    "修改任务名称后重新试运行；名称必须在当前租户和项目内唯一。");
        } else if (lower.contains("templateid") || message.contains("同步模板不存在")) {
            diagnostic(row, "IMPORT_TEMPLATE_NOT_FOUND", "templateId", true,
                    "选择当前项目中存在且可访问的模板 ID，再重新试运行。");
        } else if (lower.contains("schedule") || message.contains("调度配置")) {
            diagnostic(row, "IMPORT_SCHEDULE_CONFIG_INVALID", "scheduleConfig", true,
                    "按模板同步模式修正调度配置；非定期任务可清空该列。");
        } else if (lower.contains("priority") || message.contains("优先级")) {
            diagnostic(row, "IMPORT_PRIORITY_INVALID", "priority", true,
                    "将 priority 修改为平台支持的优先级值后重试。");
        } else if (lower.contains("tenantid") || lower.contains("projectid") || message.contains("范围")) {
            diagnostic(row, "IMPORT_SCOPE_MISMATCH", lower.contains("tenantid") ? "tenantId" : "projectId", true,
                    "使用当前项目所属的租户和项目标识，不能跨范围导入。");
        } else if (message.contains("不能创建任务草稿") || message.contains("不能直接执行")) {
            diagnostic(row, "IMPORT_TEMPLATE_PRECHECK_BLOCKED", "templateId", false,
                    "先修复模板预检查问题；也可取消立即运行并仅导入为草稿。");
        } else {
            diagnostic(row, "IMPORT_ROW_VALIDATION_FAILED", null, false,
                    "查看该行错误说明和 RAG 案例，修正后重新试运行。");
        }
    }

    private void diagnostic(SyncTaskImportRowResult row,
                            String code,
                            String field,
                            boolean repairable,
                            String action) {
        row.setErrorCode(code);
        row.setFieldName(field);
        row.setRepairable(repairable);
        row.setSuggestedAction(action);
    }

    private String ragQuery(SyncTaskImportResult result) {
        List<String> codes = result.getRows() == null ? List.of() : result.getRows().stream()
                .map(SyncTaskImportRowResult::getErrorCode)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
        return codes.isEmpty()
                ? "同步任务 CSV/XLSX 导入试运行通过后的安全确认与运行建议"
                : "同步任务导入修复案例与操作手册，错误码：" + String.join(",", codes);
    }

    private boolean isValidated(SyncTaskImportResult result) {
        return result != null && "VALIDATED".equals(result.getStatus())
                && Integer.valueOf(0).equals(result.getConflictCount())
                && Integer.valueOf(0).equals(result.getFailedCount());
    }

    private SyncTaskDefinitionExchangeCodecSupport.CellRepair toCodecRepair(SyncTaskImportCellPatch patch) {
        return new SyncTaskDefinitionExchangeCodecSupport.CellRepair(
                patch.rowNumber(), patch.columnName(), patch.expectedValue(), patch.replacementValue());
    }

    private void requireDigest(String expected, String actual, String operation) {
        if (expected == null || actual == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8))) {
            throw stateConflict(operation + "已过期或与最近一次试运行不匹配，请刷新诊断后重新确认");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new PlatformBusinessException(PlatformErrorCode.INTERNAL_ERROR, "任务导入诊断序列化失败");
        }
    }

    private String digest(String value) {
        return digest(value.getBytes(StandardCharsets.UTF_8));
    }

    private String digest(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 缺少 SHA-256", exception);
        }
    }

    private String safeFileName(String fileName, String format) {
        String fallback = "sync-task-import." + codecSupport.fileExtension(format);
        if (fileName == null || fileName.isBlank()) {
            return fallback;
        }
        String normalized = fileName.replace('\\', '_').replace('/', '_').trim();
        return normalized.length() <= 255 ? normalized : normalized.substring(normalized.length() - 255);
    }

    private String versionedFileName(String fileName, int version) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0
                ? fileName.substring(0, dot) + "-v" + version + fileName.substring(dot)
                : fileName + "-v" + version;
    }

    private PlatformBusinessException stateConflict(String message) {
        return new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT, message);
    }
}
