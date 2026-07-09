package com.czh.datasmart.govern.datasource.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.datasource.common.ApiResponse;
import com.czh.datasmart.govern.datasource.controller.dto.CreateSyncTaskRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncActionRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncApprovalRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncCompleteRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncFailRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncProgressRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncRunRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncScheduleRequest;
import com.czh.datasmart.govern.datasource.controller.dto.UpdateSyncTaskRequest;
import com.czh.datasmart.govern.datasource.entity.SyncAuditRecord;
import com.czh.datasmart.govern.datasource.entity.SyncCheckpoint;
import com.czh.datasmart.govern.datasource.entity.SyncExecution;
import com.czh.datasmart.govern.datasource.entity.SyncTask;
import com.czh.datasmart.govern.datasource.service.SyncTaskService;
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

import java.util.List;
import java.util.NoSuchElementException;

/**
 * @Author : Cui
 * @Date: 2026/4/19 19:05
 * @Description DataSmart Govern Backend - SyncTaskController.java
 * @Version:1.0.0
 *
 * 同步任务控制器。
 *
 * <p>同步任务是模板在运营层面的实例化，承载调度、运行、暂停、恢复、重试、取消、进度回写和审计查询。
 * 本轮收敛后，任务会继承模板的项目归属；所有任务列表和按 ID 访问的入口都需要校验项目范围，
 * 否则项目负责人可能通过任务 ID 读取或操作其他项目的同步任务。</p>
 *
 * <p>注意：这是 datasource-management 中的历史兼容同步任务控制面，主数据同步产品已经迁移到 data-sync 模块。
 * 普通用户侧任务是项目内自有资源，不再把任务表审批字段作为创建、列表或执行入口；权限绑定、Agent 受控工具等高风险审批
 * 仍保留在独立治理控制器中，不能和普通同步任务生命周期混用。</p>
 */
@RestController
@RequestMapping("/sync/tasks")
@RequiredArgsConstructor
public class SyncTaskController {

    private final SyncTaskService syncTaskService;
    private final DatasourceProjectScopeSupport datasourceProjectScopeSupport;

    /**
     * 创建同步任务。
     * 任务项目由模板继承，避免调用方把同一个模板实例化到另一个项目导致权限和审计口径错乱。
     */
    @PostMapping
    public ResponseEntity<ApiResponse<SyncTask>> createTask(@Valid @RequestBody CreateSyncTaskRequest request) {
        return ResponseEntity.ok(ApiResponse.success("同步任务创建成功", syncTaskService.createTask(request)));
    }

    /**
     * 更新同步任务基础配置。
     * 更新前先读取任务并校验项目范围，避免未授权用户通过 ID 修改其他项目任务。
     */
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<SyncTask>> updateTask(
            @PathVariable Long id,
            @Valid @RequestBody UpdateSyncTaskRequest request,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        getRequiredVisibleTask(id, dataScopeLevel, authorizedProjectIds);
        return ResponseEntity.ok(ApiResponse.success("同步任务更新成功", syncTaskService.updateTask(id, request)));
    }

    /**
     * 查询任务详情。
     * 详情读取必须校验任务 projectId，不能只依赖列表接口过滤。
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<SyncTask>> getTask(
            @PathVariable Long id,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        return ResponseEntity.ok(ApiResponse.success(getRequiredVisibleTask(id, dataScopeLevel, authorizedProjectIds)));
    }

    /**
     * 分页查询任务。
     * 支持租户、项目、模板、状态、负责人、优先级、启用状态和人工关注标记等运营常用筛选维度。
     */
    @GetMapping
    public ResponseEntity<ApiResponse<IPage<SyncTask>>> listTasks(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam Long tenantId,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long templateId,
            @RequestParam(required = false) String currentState,
            @RequestParam(required = false) Long ownerId,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) Boolean operatorAttentionRequired,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        DatasourceProjectVisibility visibility = datasourceProjectScopeSupport.resolveVisibility(
                projectId, null, dataScopeLevel, authorizedProjectIds);
        LambdaQueryWrapper<SyncTask> wrapper = new LambdaQueryWrapper<SyncTask>()
                .eq(SyncTask::getTenantId, tenantId)
                .eq(visibility.requestedProjectId() != null, SyncTask::getProjectId, visibility.requestedProjectId())
                .eq(templateId != null, SyncTask::getTemplateId, templateId)
                .eq(hasText(currentState), SyncTask::getCurrentState, hasText(currentState) ? currentState.toUpperCase() : null)
                .eq(ownerId != null, SyncTask::getOwnerId, ownerId)
                .eq(hasText(priority), SyncTask::getPriority, hasText(priority) ? priority.toUpperCase() : null)
                .eq(enabled != null, SyncTask::getEnabled, enabled)
                .eq(operatorAttentionRequired != null, SyncTask::getOperatorAttentionRequired, operatorAttentionRequired)
                .orderByDesc(SyncTask::getId);
        applyProjectScope(wrapper, visibility);
        return ResponseEntity.ok(ApiResponse.success(syncTaskService.page(new Page<>(safeCurrent(current), safeSize(size)), wrapper)));
    }

    @PostMapping("/{id}/submit-approval")
    public ResponseEntity<ApiResponse<SyncTask>> submitForApproval(
            @PathVariable Long id,
            @Valid @RequestBody SyncActionRequest request,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        getRequiredVisibleTask(id, dataScopeLevel, authorizedProjectIds);
        return ResponseEntity.ok(ApiResponse.success("同步任务已提交审批", syncTaskService.submitForApproval(id, request)));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<ApiResponse<SyncTask>> approve(
            @PathVariable Long id,
            @Valid @RequestBody SyncApprovalRequest request,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        getRequiredVisibleTask(id, dataScopeLevel, authorizedProjectIds);
        return ResponseEntity.ok(ApiResponse.success("同步任务审批处理完成", syncTaskService.approve(id, request)));
    }

    @PostMapping("/{id}/schedule")
    public ResponseEntity<ApiResponse<SyncTask>> schedule(
            @PathVariable Long id,
            @Valid @RequestBody SyncScheduleRequest request,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        getRequiredVisibleTask(id, dataScopeLevel, authorizedProjectIds);
        return ResponseEntity.ok(ApiResponse.success("同步任务调度配置已更新", syncTaskService.schedule(id, request)));
    }

    @PostMapping("/{id}/enqueue")
    public ResponseEntity<ApiResponse<SyncTask>> enqueue(
            @PathVariable Long id,
            @Valid @RequestBody SyncActionRequest request,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        getRequiredVisibleTask(id, dataScopeLevel, authorizedProjectIds);
        return ResponseEntity.ok(ApiResponse.success("同步任务已进入待认领队列", syncTaskService.enqueue(id, request)));
    }

    @PostMapping("/{id}/run")
    public ResponseEntity<ApiResponse<SyncTask>> run(
            @PathVariable Long id,
            @Valid @RequestBody SyncRunRequest request,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        getRequiredVisibleTask(id, dataScopeLevel, authorizedProjectIds);
        return ResponseEntity.ok(ApiResponse.success("同步任务已启动", syncTaskService.run(id, request)));
    }

    @PostMapping("/{id}/pause")
    public ResponseEntity<ApiResponse<SyncTask>> pause(
            @PathVariable Long id,
            @Valid @RequestBody SyncActionRequest request,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        getRequiredVisibleTask(id, dataScopeLevel, authorizedProjectIds);
        return ResponseEntity.ok(ApiResponse.success("同步任务已暂停", syncTaskService.pause(id, request)));
    }

    @PostMapping("/{id}/resume")
    public ResponseEntity<ApiResponse<SyncTask>> resume(
            @PathVariable Long id,
            @Valid @RequestBody SyncActionRequest request,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        getRequiredVisibleTask(id, dataScopeLevel, authorizedProjectIds);
        return ResponseEntity.ok(ApiResponse.success("同步任务已恢复排队", syncTaskService.resume(id, request)));
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<ApiResponse<SyncTask>> retry(
            @PathVariable Long id,
            @Valid @RequestBody SyncActionRequest request,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        getRequiredVisibleTask(id, dataScopeLevel, authorizedProjectIds);
        return ResponseEntity.ok(ApiResponse.success("同步任务已发起重试", syncTaskService.retry(id, request)));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<ApiResponse<SyncTask>> cancel(
            @PathVariable Long id,
            @Valid @RequestBody SyncActionRequest request,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        getRequiredVisibleTask(id, dataScopeLevel, authorizedProjectIds);
        return ResponseEntity.ok(ApiResponse.success("同步任务已取消", syncTaskService.cancel(id, request)));
    }

    /**
     * 执行器或调度中心回写任务进度。
     * 当前也做项目校验，后续如果执行器改为服务账号签名，可再走独立的执行器鉴权链路。
     */
    @PostMapping("/{id}/progress")
    public ResponseEntity<ApiResponse<SyncTask>> reportProgress(
            @PathVariable Long id,
            @Valid @RequestBody SyncProgressRequest request,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        getRequiredVisibleTask(id, dataScopeLevel, authorizedProjectIds);
        return ResponseEntity.ok(ApiResponse.success("同步任务进度已更新", syncTaskService.reportProgress(id, request)));
    }

    @PostMapping("/{id}/complete")
    public ResponseEntity<ApiResponse<SyncTask>> completeExecution(
            @PathVariable Long id,
            @Valid @RequestBody SyncCompleteRequest request,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        getRequiredVisibleTask(id, dataScopeLevel, authorizedProjectIds);
        return ResponseEntity.ok(ApiResponse.success("同步任务执行完成", syncTaskService.completeExecution(id, request)));
    }

    @PostMapping("/{id}/fail")
    public ResponseEntity<ApiResponse<SyncTask>> failExecution(
            @PathVariable Long id,
            @Valid @RequestBody SyncFailRequest request,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        getRequiredVisibleTask(id, dataScopeLevel, authorizedProjectIds);
        return ResponseEntity.ok(ApiResponse.success("同步任务失败状态已回写", syncTaskService.failExecution(id, request)));
    }

    @GetMapping("/{id}/executions")
    public ResponseEntity<ApiResponse<List<SyncExecution>>> listExecutions(
            @PathVariable Long id,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        getRequiredVisibleTask(id, dataScopeLevel, authorizedProjectIds);
        return ResponseEntity.ok(ApiResponse.success(syncTaskService.listExecutions(id)));
    }

    @GetMapping("/{id}/checkpoints")
    public ResponseEntity<ApiResponse<List<SyncCheckpoint>>> listCheckpoints(
            @PathVariable Long id,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        getRequiredVisibleTask(id, dataScopeLevel, authorizedProjectIds);
        return ResponseEntity.ok(ApiResponse.success(syncTaskService.listCheckpoints(id)));
    }

    @GetMapping("/{id}/audit")
    public ResponseEntity<ApiResponse<List<SyncAuditRecord>>> listAuditRecords(
            @PathVariable Long id,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        getRequiredVisibleTask(id, dataScopeLevel, authorizedProjectIds);
        return ResponseEntity.ok(ApiResponse.success(syncTaskService.listAuditRecords(id)));
    }

    private SyncTask getRequiredVisibleTask(Long id, String dataScopeLevel, String authorizedProjectIds) {
        SyncTask task = syncTaskService.getById(id);
        if (task == null) {
            throw new NoSuchElementException("同步任务不存在: " + id);
        }
        DatasourceProjectVisibility visibility = datasourceProjectScopeSupport.resolveVisibility(
                null, null, dataScopeLevel, authorizedProjectIds);
        datasourceProjectScopeSupport.validateProjectReadable(task.getProjectId(), visibility, "同步任务");
        return task;
    }

    private void applyProjectScope(LambdaQueryWrapper<SyncTask> wrapper, DatasourceProjectVisibility visibility) {
        if (!visibility.projectScopeEnforced()) {
            return;
        }
        if (visibility.authorizedProjectIds().isEmpty()) {
            wrapper.apply("1 = 0");
            return;
        }
        if (visibility.requestedProjectId() == null) {
            wrapper.in(SyncTask::getProjectId, visibility.authorizedProjectIds());
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 规整页码。
     *
     * <p>历史接口过去直接信任前端 current/size。当前普通业务列表已经统一要求最大展示 100 条，
     * 因此旧控制面也需要同步做后端兜底，避免脚本或旧页面绕过前端限制一次拉取过多任务定义。</p>
     */
    private long safeCurrent(Integer current) {
        return current == null || current <= 0 ? 1L : current.longValue();
    }

    private long safeSize(Integer size) {
        if (size == null || size <= 0) {
            return 10L;
        }
        return Math.min(size.longValue(), 100L);
    }
}
