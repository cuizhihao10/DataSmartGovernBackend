package com.czh.datasmart.govern.datasource.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.datasource.common.ApiResponse;
import com.czh.datasmart.govern.datasource.controller.dto.CreateSyncTemplateRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncActionRequest;
import com.czh.datasmart.govern.datasource.controller.dto.UpdateSyncTemplateRequest;
import com.czh.datasmart.govern.datasource.entity.SyncTemplate;
import com.czh.datasmart.govern.datasource.service.SyncTemplateService;
import com.czh.datasmart.govern.datasource.service.support.DatasourceProjectScopeSupport;
import com.czh.datasmart.govern.datasource.service.support.DatasourceProjectVisibility;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.NoSuchElementException;

/**
 * @Author : Cui
 * @Date: 2026/4/18 23:03
 * @Description DataSmart Govern Backend - SyncTemplateController.java
 * @Version:1.0.0
 *
 * 同步模板控制器。
 *
 * <p>模板接口的定位不是直接执行同步，而是承载“配置、预览、校验、复用”这几个控制面职责。
 * 模板一旦被多个任务复用，就会影响后续任务的源端对象、目标对象、同步模式、写入策略和字段映射。
 * 因此模板本身也必须纳入项目级数据范围，不能只做租户级隔离。</p>
 */
@RestController
@RequestMapping("/sync/templates")
@RequiredArgsConstructor
public class SyncTemplateController {

    private final SyncTemplateService syncTemplateService;
    private final DatasourceProjectScopeSupport datasourceProjectScopeSupport;

    /**
     * 创建同步模板。
     * 请求体必须携带 projectId；创建后的任务会继承模板项目，保证模板、任务、审计和项目看板口径一致。
     */
    @PostMapping
    public ResponseEntity<ApiResponse<SyncTemplate>> createTemplate(
            @Valid @RequestBody CreateSyncTemplateRequest request) {
        return ResponseEntity.ok(ApiResponse.success("同步模板创建成功", syncTemplateService.createTemplate(request)));
    }

    /**
     * 更新同步模板。
     * 当前不允许通过更新接口迁移模板项目，避免已有任务、审计和权限归属出现不一致；后续可单独设计模板转移审批流。
     */
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<SyncTemplate>> updateTemplate(
            @PathVariable Long id,
            @Valid @RequestBody UpdateSyncTemplateRequest request,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        getRequiredVisibleTemplate(id, dataScopeLevel, authorizedProjectIds);
        return ResponseEntity.ok(ApiResponse.success("同步模板更新成功", syncTemplateService.updateTemplate(id, request)));
    }

    /**
     * 查询同步模板详情。
     * 详情接口会进行项目级校验，防止用户绕过列表过滤直接读取其他项目模板。
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<SyncTemplate>> getTemplate(
            @PathVariable Long id,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        return ResponseEntity.ok(ApiResponse.success(getRequiredVisibleTemplate(id, dataScopeLevel, authorizedProjectIds)));
    }

    /**
     * 分页查询模板。
     * 当 gateway 明确声明 PROJECT 范围时，后端会自动按授权项目集合过滤；空集合返回空结果。
     */
    @GetMapping
    public ResponseEntity<ApiResponse<IPage<SyncTemplate>>> listTemplates(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam Long tenantId,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) String syncMode,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        DatasourceProjectVisibility visibility = datasourceProjectScopeSupport.resolveVisibility(
                projectId, null, dataScopeLevel, authorizedProjectIds);
        LambdaQueryWrapper<SyncTemplate> wrapper = new LambdaQueryWrapper<SyncTemplate>()
                .eq(SyncTemplate::getTenantId, tenantId)
                .eq(visibility.requestedProjectId() != null, SyncTemplate::getProjectId, visibility.requestedProjectId())
                .eq(enabled != null, SyncTemplate::getEnabled, enabled)
                .eq(hasText(syncMode), SyncTemplate::getSyncMode, hasText(syncMode) ? syncMode.toUpperCase() : null)
                .orderByDesc(SyncTemplate::getCreateTime);
        applyProjectScope(wrapper, visibility);
        return ResponseEntity.ok(ApiResponse.success(syncTemplateService.page(new Page<>(current, size), wrapper)));
    }

    /**
     * 智能校验模板。
     * 校验会读取模板和两端数据源信息，因此在校验前先确认调用方能访问该模板所属项目。
     */
    @PostMapping("/{id}/validate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> validateTemplate(
            @PathVariable Long id,
            @Valid @RequestBody SyncActionRequest request,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        getRequiredVisibleTemplate(id, dataScopeLevel, authorizedProjectIds);
        return ResponseEntity.ok(ApiResponse.success(
                "模板智能校验完成",
                syncTemplateService.validateTemplate(id, request.getActorId(), request.getActorRole(), request.getActorTenantId())));
    }

    /**
     * 生成模板预览摘要。
     * 预览面向前端详情页和运维排查，不做真实外部库探查，但仍属于模板详情读取，必须校验项目范围。
     */
    @GetMapping("/{id}/preview")
    public ResponseEntity<ApiResponse<Map<String, Object>>> previewTemplate(
            @PathVariable Long id,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        getRequiredVisibleTemplate(id, dataScopeLevel, authorizedProjectIds);
        return ResponseEntity.ok(ApiResponse.success(syncTemplateService.previewTemplate(id)));
    }

    private SyncTemplate getRequiredVisibleTemplate(Long id, String dataScopeLevel, String authorizedProjectIds) {
        SyncTemplate template = syncTemplateService.getById(id);
        if (template == null) {
            throw new NoSuchElementException("同步模板不存在: " + id);
        }
        DatasourceProjectVisibility visibility = datasourceProjectScopeSupport.resolveVisibility(
                null, null, dataScopeLevel, authorizedProjectIds);
        datasourceProjectScopeSupport.validateProjectReadable(template.getProjectId(), visibility, "同步模板");
        return template;
    }

    private void applyProjectScope(LambdaQueryWrapper<SyncTemplate> wrapper, DatasourceProjectVisibility visibility) {
        if (!visibility.projectScopeEnforced()) {
            return;
        }
        if (visibility.authorizedProjectIds().isEmpty()) {
            wrapper.apply("1 = 0");
            return;
        }
        if (visibility.requestedProjectId() == null) {
            wrapper.in(SyncTemplate::getProjectId, visibility.authorizedProjectIds());
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
