package com.czh.datasmart.govern.datasource.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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

import java.util.List;
import java.util.NoSuchElementException;

/**
 * @Author : Cui
 * @Date: 2026/4/19 19:05
 * @Description DataSmart Govern Backend - SyncTaskController.java
 * @Version:1.0.0
 *
 * 同步任务控制器。
 * 这组接口对应数据同步产品最核心的控制面能力：
 * - 任务创建和更新；
 * - 审批、调度、运行；
 * - 暂停、恢复、重试、取消；
 * - 执行进度回写；
 * - 执行历史、检查点和审计轨迹查询。
 *
 * 这里刻意把“普通任务动作”和“管理员强制动作”拆开：
 * - 普通任务动作放在 `/sync/tasks` 下；
 * - 管理员动作放在 `/sync/admin/tasks` 下。
 *
 * 这样做的好处是：
 * 1. 路由语义更清晰。
 * 2. 后续接权限中心时更容易做资源分类。
 * 3. 审计和菜单管理也更容易区分用户动作与管理员动作。
 */
@RestController
@RequestMapping("/sync/tasks")
@RequiredArgsConstructor
public class SyncTaskController {

    private final SyncTaskService syncTaskService;

    @PostMapping
    public ResponseEntity<ApiResponse<SyncTask>> createTask(@Valid @RequestBody CreateSyncTaskRequest request) {
        return ResponseEntity.ok(ApiResponse.success("同步任务创建成功", syncTaskService.createTask(request)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<SyncTask>> updateTask(
            @PathVariable Long id,
            @Valid @RequestBody UpdateSyncTaskRequest request) {
        return ResponseEntity.ok(ApiResponse.success("同步任务更新成功", syncTaskService.updateTask(id, request)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<SyncTask>> getTask(@PathVariable Long id) {
        SyncTask task = syncTaskService.getById(id);
        if (task == null) {
            throw new NoSuchElementException("同步任务不存在: " + id);
        }
        return ResponseEntity.ok(ApiResponse.success(task));
    }

    /**
     * 分页查询任务。
     * 这一步先支持运营场景中最常用的一组筛选维度，后续可以继续扩展到日期范围、连接器类型、模板风险等级等。
     */
    @GetMapping
    public ResponseEntity<ApiResponse<IPage<SyncTask>>> listTasks(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam Long tenantId,
            @RequestParam(required = false) Long templateId,
            @RequestParam(required = false) String currentState,
            @RequestParam(required = false) String approvalState,
            @RequestParam(required = false) Long ownerId,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) Boolean operatorAttentionRequired) {
        LambdaQueryWrapper<SyncTask> wrapper = new LambdaQueryWrapper<SyncTask>()
                .eq(SyncTask::getTenantId, tenantId)
                .eq(templateId != null, SyncTask::getTemplateId, templateId)
                .eq(currentState != null && !currentState.isBlank(), SyncTask::getCurrentState,
                        currentState == null ? null : currentState.toUpperCase())
                .eq(approvalState != null && !approvalState.isBlank(), SyncTask::getApprovalState,
                        approvalState == null ? null : approvalState.toUpperCase())
                .eq(ownerId != null, SyncTask::getOwnerId, ownerId)
                .eq(priority != null && !priority.isBlank(), SyncTask::getPriority,
                        priority == null ? null : priority.toUpperCase())
                .eq(enabled != null, SyncTask::getEnabled, enabled)
                .eq(operatorAttentionRequired != null, SyncTask::getOperatorAttentionRequired, operatorAttentionRequired)
                .orderByDesc(SyncTask::getCreateTime);
        return ResponseEntity.ok(ApiResponse.success(syncTaskService.page(new Page<>(current, size), wrapper)));
    }

    @PostMapping("/{id}/submit-approval")
    public ResponseEntity<ApiResponse<SyncTask>> submitForApproval(
            @PathVariable Long id,
            @Valid @RequestBody SyncActionRequest request) {
        return ResponseEntity.ok(ApiResponse.success("同步任务已提交审批", syncTaskService.submitForApproval(id, request)));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<ApiResponse<SyncTask>> approve(
            @PathVariable Long id,
            @Valid @RequestBody SyncApprovalRequest request) {
        return ResponseEntity.ok(ApiResponse.success("同步任务审批处理完成", syncTaskService.approve(id, request)));
    }

    @PostMapping("/{id}/schedule")
    public ResponseEntity<ApiResponse<SyncTask>> schedule(
            @PathVariable Long id,
            @Valid @RequestBody SyncScheduleRequest request) {
        return ResponseEntity.ok(ApiResponse.success("同步任务调度配置已更新", syncTaskService.schedule(id, request)));
    }

    /**
     * 将任务推进到待认领队列。
     * 它和直接 run 的区别在于：
     * - enqueue 面向“控制面先排队，再由执行器认领”；
     * - run 面向“人工直接触发并立即进入运行态”。
     */
    @PostMapping("/{id}/enqueue")
    public ResponseEntity<ApiResponse<SyncTask>> enqueue(
            @PathVariable Long id,
            @Valid @RequestBody SyncActionRequest request) {
        return ResponseEntity.ok(ApiResponse.success("同步任务已进入待认领队列", syncTaskService.enqueue(id, request)));
    }

    @PostMapping("/{id}/run")
    public ResponseEntity<ApiResponse<SyncTask>> run(
            @PathVariable Long id,
            @Valid @RequestBody SyncRunRequest request) {
        return ResponseEntity.ok(ApiResponse.success("同步任务已启动", syncTaskService.run(id, request)));
    }

    @PostMapping("/{id}/pause")
    public ResponseEntity<ApiResponse<SyncTask>> pause(
            @PathVariable Long id,
            @Valid @RequestBody SyncActionRequest request) {
        return ResponseEntity.ok(ApiResponse.success("同步任务已暂停", syncTaskService.pause(id, request)));
    }

    @PostMapping("/{id}/resume")
    public ResponseEntity<ApiResponse<SyncTask>> resume(
            @PathVariable Long id,
            @Valid @RequestBody SyncActionRequest request) {
        return ResponseEntity.ok(ApiResponse.success("同步任务已恢复排队", syncTaskService.resume(id, request)));
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<ApiResponse<SyncTask>> retry(
            @PathVariable Long id,
            @Valid @RequestBody SyncActionRequest request) {
        return ResponseEntity.ok(ApiResponse.success("同步任务已发起重试", syncTaskService.retry(id, request)));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<ApiResponse<SyncTask>> cancel(
            @PathVariable Long id,
            @Valid @RequestBody SyncActionRequest request) {
        return ResponseEntity.ok(ApiResponse.success("同步任务已取消", syncTaskService.cancel(id, request)));
    }

    /**
     * 供未来执行器或调度中心持续回写运行指标和检查点。
     */
    @PostMapping("/{id}/progress")
    public ResponseEntity<ApiResponse<SyncTask>> reportProgress(
            @PathVariable Long id,
            @Valid @RequestBody SyncProgressRequest request) {
        return ResponseEntity.ok(ApiResponse.success("同步任务进度已更新", syncTaskService.reportProgress(id, request)));
    }

    @PostMapping("/{id}/complete")
    public ResponseEntity<ApiResponse<SyncTask>> completeExecution(
            @PathVariable Long id,
            @Valid @RequestBody SyncCompleteRequest request) {
        return ResponseEntity.ok(ApiResponse.success("同步任务执行完成", syncTaskService.completeExecution(id, request)));
    }

    @PostMapping("/{id}/fail")
    public ResponseEntity<ApiResponse<SyncTask>> failExecution(
            @PathVariable Long id,
            @Valid @RequestBody SyncFailRequest request) {
        return ResponseEntity.ok(ApiResponse.success("同步任务失败状态已回写", syncTaskService.failExecution(id, request)));
    }

    /**
     * 查询执行历史。
     */
    @GetMapping("/{id}/executions")
    public ResponseEntity<ApiResponse<List<SyncExecution>>> listExecutions(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(syncTaskService.listExecutions(id)));
    }

    /**
     * 查询检查点历史。
     * 这对断点续跑、补数和回放场景尤其关键。
     */
    @GetMapping("/{id}/checkpoints")
    public ResponseEntity<ApiResponse<List<SyncCheckpoint>>> listCheckpoints(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(syncTaskService.listCheckpoints(id)));
    }

    /**
     * 查询任务审计轨迹。
     */
    @GetMapping("/{id}/audit")
    public ResponseEntity<ApiResponse<List<SyncAuditRecord>>> listAuditRecords(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(syncTaskService.listAuditRecords(id)));
    }
}
