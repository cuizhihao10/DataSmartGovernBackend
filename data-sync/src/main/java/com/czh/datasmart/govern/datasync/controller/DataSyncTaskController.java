/**
 * @Author : Cui
 * @Date: 2026/05/07 21:33
 * @Description DataSmart Govern Backend - DataSyncTaskController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller;

import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.api.PlatformPageResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.datasync.controller.dto.CreateSyncTaskRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskLifecycleOperationRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskOperationResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.support.SyncActorContextHeaderSupport;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
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
 * 同步任务 API。
 *
 * <p>任务 API 面向“运营对象”：创建后可以进入调度、运行、暂停、恢复、重试、取消、归档等生命周期。
 * 当前实现已经具备基础控制面：创建、查询、入队运行、暂停、恢复、重试和取消。
 * 真正的数据搬运仍由 execution 租约、执行器回调和后续 connector worker 完成，Controller 不直接读写源端或目标端数据。
 */
@RestController
@RequestMapping("/sync-tasks")
@RequiredArgsConstructor
public class DataSyncTaskController {

    private final DataSyncService dataSyncService;

    /**
     * 创建同步任务。
     */
    @PostMapping
    public PlatformApiResponse<SyncTask> createTask(
            @Valid @RequestBody CreateSyncTaskRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        SyncTask task = dataSyncService.createTask(request, actorContext(tenantId, actorId, actorRole, traceId, headers));
        return PlatformApiResponse.success("同步任务创建成功", task, traceId);
    }

    /**
     * 分页查询同步任务。
     */
    @GetMapping
    public PlatformApiResponse<PlatformPageResponse<SyncTask>> pageTasks(
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long workspaceId,
            @RequestParam(required = false) Long templateId,
            @RequestParam(required = false) Long ownerId,
            @RequestParam(required = false) String currentState,
            @RequestParam(required = false) String approvalState,
            @RequestParam(required = false) String triggerType,
            @RequestParam(defaultValue = "1") Long current,
            @RequestParam(defaultValue = "20") Long size,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        SyncTaskQueryCriteria criteria = new SyncTaskQueryCriteria(
                tenantId, projectId, workspaceId, templateId, ownerId, currentState, approvalState, triggerType, current, size);
        return PlatformApiResponse.success(dataSyncService.pageTasks(
                criteria, actorContext(actorTenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    /**
     * 查询同步任务详情。
     */
    @GetMapping("/{id}")
    public PlatformApiResponse<SyncTask> getTask(
            @PathVariable Long id,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        return PlatformApiResponse.success(dataSyncService.getTask(
                id, actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    /**
     * 手动运行同步任务。
     *
     * <p>当前实现只把任务推进到 QUEUED。
     * 后续执行器上线后，该动作会进一步触发 task-management 或 data-sync worker 认领流程。
     */
    @PostMapping("/{id}/run")
    public PlatformApiResponse<SyncTaskOperationResult> runTask(
            @PathVariable Long id,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        return PlatformApiResponse.success("同步任务已提交运行",
                dataSyncService.runTask(id, actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    /**
     * 暂停同步任务。
     *
     * <p>路由语义：
     * - path 中的 id 表示被控制的同步任务；
     * - request.reason 是可选低敏操作说明，会进入审计摘要；
     * - 返回值只包含任务 ID、目标状态和低敏说明，不返回源端连接、目标端连接、SQL、样本数据或 worker 内部信息。
     *
     * <p>执行边界：
     * 对 QUEUED execution，暂停会阻止后续 worker 认领；
     * 对 RUNNING/RETRYING execution，暂停会先写入控制面状态，后续 worker 需要在心跳或 checkpoint 阶段协作停止。
     */
    @PostMapping("/{id}/pause")
    public PlatformApiResponse<SyncTaskOperationResult> pauseTask(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) SyncTaskLifecycleOperationRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        return PlatformApiResponse.success("同步任务已提交暂停",
                dataSyncService.pauseTask(id, request, actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    /**
     * 恢复同步任务。
     *
     * <p>恢复只允许从 PAUSED 状态出发，并会创建新的 QUEUED execution。
     * 这样旧 execution 可以保留暂停历史，新 execution 继续复用现有租约认领、心跳、checkpoint 和完成/失败回调协议。
     */
    @PostMapping("/{id}/resume")
    public PlatformApiResponse<SyncTaskOperationResult> resumeTask(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) SyncTaskLifecycleOperationRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        return PlatformApiResponse.success("同步任务已提交恢复",
                dataSyncService.resumeTask(id, request, actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    /**
     * 重试同步任务。
     *
     * <p>普通重试面向 FAILED 和 PARTIALLY_SUCCEEDED。
     * 如果任务已经进入 AWAITING_OPERATOR_ACTION，说明自动恢复已经不再安全，必须改走人工介入接口，
     * 由运营人员先确认问题处理结果，再决定是否重跑。
     */
    @PostMapping("/{id}/retry")
    public PlatformApiResponse<SyncTaskOperationResult> retryTask(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) SyncTaskLifecycleOperationRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        return PlatformApiResponse.success("同步任务已提交重试",
                dataSyncService.retryTask(id, request, actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    /**
     * 取消同步任务。
     *
     * <p>取消是终态控制动作，用于明确表达“这个任务不再继续执行”。
     * 如果最近 execution 仍在 QUEUED/RUNNING/RETRYING/PAUSED 窗口，服务端会同步写入 CANCELLED 控制信号；
     * 如果最近 execution 已经成功或失败，则保留 execution 历史事实，只把任务主状态关闭到 CANCELLED。
     */
    @PostMapping("/{id}/cancel")
    public PlatformApiResponse<SyncTaskOperationResult> cancelTask(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) SyncTaskLifecycleOperationRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        return PlatformApiResponse.success("同步任务已提交取消",
                dataSyncService.cancelTask(id, request, actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    private SyncActorContext actorContext(Long tenantId, Long actorId, String actorRole, String traceId, HttpHeaders headers) {
        return SyncActorContextHeaderSupport.fromHeaders(tenantId, actorId, actorRole, traceId, headers);
    }
}
