package com.czh.datasmart.govern.datasource.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.czh.datasmart.govern.datasource.common.ApiResponse;
import com.czh.datasmart.govern.datasource.controller.dto.CreateSyncTemplateRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncActionRequest;
import com.czh.datasmart.govern.datasource.controller.dto.UpdateSyncTemplateRequest;
import com.czh.datasmart.govern.datasource.entity.SyncTemplate;
import com.czh.datasmart.govern.datasource.service.SyncTemplateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
 * 模板接口的定位不是直接执行同步，而是承载“配置、预览、校验、复用”这几个控制面职责。
 *
 * 随着这一轮扩展，模板校验已经不再只是检查数据源是否存在，
 * 而是会进一步校验源/目标对象、主键、增量字段、字段映射与结构风险。
 */
@RestController
@RequestMapping("/sync/templates")
@RequiredArgsConstructor
public class SyncTemplateController {

    private final SyncTemplateService syncTemplateService;

    @PostMapping
    public ResponseEntity<ApiResponse<SyncTemplate>> createTemplate(
            @Valid @RequestBody CreateSyncTemplateRequest request) {
        return ResponseEntity.ok(ApiResponse.success("同步模板创建成功", syncTemplateService.createTemplate(request)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<SyncTemplate>> updateTemplate(
            @PathVariable Long id,
            @Valid @RequestBody UpdateSyncTemplateRequest request) {
        return ResponseEntity.ok(ApiResponse.success("同步模板更新成功", syncTemplateService.updateTemplate(id, request)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<SyncTemplate>> getTemplate(@PathVariable Long id) {
        SyncTemplate template = syncTemplateService.getById(id);
        if (template == null) {
            throw new NoSuchElementException("同步模板不存在: " + id);
        }
        return ResponseEntity.ok(ApiResponse.success(template));
    }

    /**
     * 分页查询模板。
     * 当前保留了租户、启停状态、同步模式这几个最常见的管理筛选维度。
     */
    @GetMapping
    public ResponseEntity<ApiResponse<IPage<SyncTemplate>>> listTemplates(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam Long tenantId,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) String syncMode) {
        LambdaQueryWrapper<SyncTemplate> wrapper = new LambdaQueryWrapper<SyncTemplate>()
                .eq(SyncTemplate::getTenantId, tenantId)
                .eq(enabled != null, SyncTemplate::getEnabled, enabled)
                .eq(syncMode != null && !syncMode.isBlank(), SyncTemplate::getSyncMode, syncMode == null ? null : syncMode.toUpperCase())
                .orderByDesc(SyncTemplate::getCreateTime);
        return ResponseEntity.ok(ApiResponse.success(syncTemplateService.page(new Page<>(current, size), wrapper)));
    }

    /**
     * 智能校验模板。
     * 这个接口建议在“保存后校验”“上线前检查”“提交审批前检查”等场景中复用。
     */
    @PostMapping("/{id}/validate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> validateTemplate(
            @PathVariable Long id,
            @Valid @RequestBody SyncActionRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "模板智能校验完成",
                syncTemplateService.validateTemplate(id, request.getActorId(), request.getActorRole())));
    }

    @GetMapping("/{id}/preview")
    public ResponseEntity<ApiResponse<Map<String, Object>>> previewTemplate(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(syncTemplateService.previewTemplate(id)));
    }
}
