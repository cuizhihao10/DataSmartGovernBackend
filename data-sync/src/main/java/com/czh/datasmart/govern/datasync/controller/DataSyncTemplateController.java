/**
 * @Author : Cui
 * @Date: 2026/05/07 21:33
 * @Description DataSmart Govern Backend - DataSyncTemplateController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller;

import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.api.PlatformPageResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.datasync.controller.dto.CreateSyncTemplateRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskOperationResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTemplateQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.support.SyncActorContextHeaderSupport;
import com.czh.datasmart.govern.datasync.entity.SyncTemplate;
import com.czh.datasmart.govern.datasync.service.DataSyncService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 同步模板 API。
 *
 * <p>模板 API 面向“同步配置定义”，不直接执行数据搬运。
 * 这样做的好处是：用户可以先创建模板、校验模板、预览影响范围，再决定是否创建任务或提交审批。
 */
@RestController
@RequestMapping("/sync-templates")
@RequiredArgsConstructor
public class DataSyncTemplateController {

    private final DataSyncService dataSyncService;

    /**
     * 创建同步模板。
     */
    @PostMapping
    public PlatformApiResponse<SyncTemplate> createTemplate(
            @Valid @RequestBody CreateSyncTemplateRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        SyncTemplate template = dataSyncService.createTemplate(request, actorContext(tenantId, actorId, actorRole, traceId, headers));
        return PlatformApiResponse.success("同步模板创建成功", template, traceId);
    }

    /**
     * 分页查询同步模板。
     */
    @GetMapping
    public PlatformApiResponse<PlatformPageResponse<SyncTemplate>> pageTemplates(
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long workspaceId,
            @RequestParam(required = false) Long sourceDatasourceId,
            @RequestParam(required = false) Long targetDatasourceId,
            @RequestParam(required = false) String syncMode,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(defaultValue = "1") Long current,
            @RequestParam(defaultValue = "20") Long size,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        SyncTemplateQueryCriteria criteria = new SyncTemplateQueryCriteria(
                tenantId, projectId, workspaceId, sourceDatasourceId, targetDatasourceId, syncMode, enabled, current, size);
        return PlatformApiResponse.success(dataSyncService.pageTemplates(
                criteria, actorContext(actorTenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    /**
     * 查询同步模板详情。
     */
    @GetMapping("/{id}")
    public PlatformApiResponse<SyncTemplate> getTemplate(
            @PathVariable Long id,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        return PlatformApiResponse.success(dataSyncService.getTemplate(
                id, actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    /**
     * 校验同步模板。
     */
    @PostMapping("/{id}/validate")
    public PlatformApiResponse<SyncTaskOperationResult> validateTemplate(
            @PathVariable Long id,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        return PlatformApiResponse.success("同步模板校验完成",
                dataSyncService.validateTemplate(id, actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    private SyncActorContext actorContext(Long tenantId, Long actorId, String actorRole, String traceId, HttpHeaders headers) {
        return SyncActorContextHeaderSupport.fromHeaders(tenantId, actorId, actorRole, traceId, headers);
    }
}
