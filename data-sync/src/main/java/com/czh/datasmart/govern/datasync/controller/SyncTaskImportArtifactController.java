/**
 * @Author : Cui
 * @Date: 2026/07/22 18:45
 * @Description DataSmart Govern Backend - SyncTaskImportArtifactController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller;

import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskImportArtifactCommitResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskImportArtifactDryRunResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskImportArtifactView;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskImportCommitRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskImportRepairRequest;
import com.czh.datasmart.govern.datasync.controller.support.SyncActorContextHeaderSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTaskImportArtifactService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * HTTP boundary for Agent-assisted task import artifacts.
 *
 * <p>Upload remains a user/browser action. Later Agent tools receive only the
 * artifact reference. Repair and commit are separate POST operations so the UI can
 * present the model proposal and collect explicit confirmation before side effects.</p>
 */
@RestController
@RequestMapping("/sync-task-import-artifacts")
@RequiredArgsConstructor
public class SyncTaskImportArtifactController {

    private final SyncTaskImportArtifactService artifactService;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PlatformApiResponse<SyncTaskImportArtifactView> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String format,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        SyncTaskImportArtifactView result = artifactService.upload(
                read(file), file == null ? null : file.getOriginalFilename(), format,
                actorContext(tenantId, actorId, actorRole, traceId, headers));
        return PlatformApiResponse.success("任务导入制品已上传", result, traceId);
    }

    @GetMapping("/{artifactRef}")
    public PlatformApiResponse<SyncTaskImportArtifactView> detail(
            @PathVariable String artifactRef,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        return PlatformApiResponse.success("任务导入制品查询成功",
                artifactService.detail(artifactRef, actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    @PostMapping("/{artifactRef}/dry-run")
    public PlatformApiResponse<SyncTaskImportArtifactDryRunResult> dryRun(
            @PathVariable String artifactRef,
            @RequestParam(defaultValue = "false") Boolean runImmediately,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        return PlatformApiResponse.success("任务导入制品试运行完成",
                artifactService.dryRun(artifactRef, Boolean.TRUE.equals(runImmediately),
                        actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    @PostMapping("/{artifactRef}/repairs")
    public PlatformApiResponse<SyncTaskImportArtifactView> repair(
            @PathVariable String artifactRef,
            @Valid @RequestBody SyncTaskImportRepairRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        return PlatformApiResponse.success("已按确认方案创建修复制品新版本",
                artifactService.applyRepair(artifactRef, request,
                        actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    @PostMapping("/{artifactRef}/commit")
    public PlatformApiResponse<SyncTaskImportArtifactCommitResult> commit(
            @PathVariable String artifactRef,
            @Valid @RequestBody SyncTaskImportCommitRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        return PlatformApiResponse.success("任务导入制品已确认提交",
                artifactService.commit(artifactRef, request,
                        actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    private SyncActorContext actorContext(Long tenantId,
                                          Long actorId,
                                          String actorRole,
                                          String traceId,
                                          HttpHeaders headers) {
        return SyncActorContextHeaderSupport.fromHeaders(tenantId, actorId, actorRole, traceId, headers);
    }

    private byte[] read(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "任务导入制品文件不能为空");
        }
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "读取任务导入制品失败");
        }
    }
}
