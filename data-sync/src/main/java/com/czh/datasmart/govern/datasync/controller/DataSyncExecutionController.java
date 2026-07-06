/**
 * @Author : Cui
 * @Date: 2026/05/07 21:42
 * @Description DataSmart Govern Backend - DataSyncExecutionController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller;

import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.api.PlatformPageResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncAuditQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncCheckpointQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncErrorSampleQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncObjectExecutionQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncObjectExecutionView;
import com.czh.datasmart.govern.datasync.controller.dto.SyncObjectRetryRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncObjectRetryResult;
import com.czh.datasmart.govern.datasync.controller.support.SyncActorContextHeaderSupport;
import com.czh.datasmart.govern.datasync.entity.SyncAuditRecord;
import com.czh.datasmart.govern.datasync.entity.SyncCheckpoint;
import com.czh.datasmart.govern.datasync.entity.SyncErrorSample;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.service.DataSyncService;
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
 * 数据同步执行与追踪 API。
 *
 * <p>这个控制器只承载“任务运行后的可观测事实”：执行历史、checkpoint、错误样本和审计。
 * 这样可以让 DataSyncTaskController 继续聚焦任务定义与生命周期动作，避免一个 Controller 又慢慢长成万能控制器。
 */
@RestController
@RequestMapping("/sync-tasks/{taskId}")
@RequiredArgsConstructor
public class DataSyncExecutionController {

    private final DataSyncService dataSyncService;

    /**
     * 查询某个同步任务的执行历史。
     *
     * <p>执行历史用于回答“这个任务跑过几次、每次是什么状态、是否失败、由谁触发”。
     */
    @GetMapping("/executions")
    public PlatformApiResponse<PlatformPageResponse<SyncExecution>> pageExecutions(
            @PathVariable Long taskId,
            @RequestParam(required = false) String executionState,
            @RequestParam(required = false) String triggerType,
            @RequestParam(defaultValue = "1") Long current,
            @RequestParam(defaultValue = "20") Long size,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        SyncExecutionQueryCriteria criteria = new SyncExecutionQueryCriteria(taskId, executionState, triggerType, current, size);
        return PlatformApiResponse.success(dataSyncService.pageExecutions(
                criteria, actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    /**
     * 查询某次 execution 的对象级执行账本。
     *
     * <p>路由语义：</p>
     * <p>1. {@code taskId} 表示同步任务，先用于数据范围校验；</p>
     * <p>2. {@code executionId} 表示某一次真实运行；</p>
     * <p>3. {@code objects} 表示该运行内部的对象/分片级事实，例如 OBJECT_LIST 中的每张表；</p>
     * <p>4. {@code objectState/objectOrdinal} 是运维筛选条件，方便快速定位失败对象。</p>
     *
     * <p>权限与安全边界：该接口应由 gateway 标记为 {@code SYNC_EXECUTION + VIEW}。响应允许展示对象名用于排障，
     * 但不会返回 SQL、where/filter 原文、字段映射正文、连接串、凭据或样本行。</p>
     */
    @GetMapping("/executions/{executionId}/objects")
    public PlatformApiResponse<PlatformPageResponse<SyncObjectExecutionView>> pageObjectExecutions(
            @PathVariable Long taskId,
            @PathVariable Long executionId,
            @RequestParam(required = false) String objectState,
            @RequestParam(required = false) Integer objectOrdinal,
            @RequestParam(defaultValue = "1") Long current,
            @RequestParam(defaultValue = "20") Long size,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        SyncObjectExecutionQueryCriteria criteria = new SyncObjectExecutionQueryCriteria(
                taskId, executionId, objectState, objectOrdinal, current, size);
        return PlatformApiResponse.success(dataSyncService.pageObjectExecutions(
                criteria, actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    /**
     * 对某次 execution 内部的 FAILED 对象发起选择性重试。
     *
     * <p>该接口是 PARTIALLY_SUCCEEDED 真正闭环的关键：如果一个 OBJECT_LIST 任务中 10 张表有 8 张成功、2 张失败，
     * 运维人员不应该被迫整单重跑 10 张表，而应能够只重传失败对象。服务端会把选中的 FAILED 对象重置为 PENDING，
     * 并把父 execution 放回 QUEUED，后续 worker 重新认领时会自动跳过已成功对象。</p>
     *
     * <p>请求体可以为空：为空代表重试当前 execution 下全部 FAILED 对象；也可以传 objectExecutionIds 或
     * objectOrdinals 做精确选择。成功对象、运行中对象、取消对象都不会被重试。</p>
     */
    @PostMapping("/executions/{executionId}/objects/retry")
    public PlatformApiResponse<SyncObjectRetryResult> retryObjectExecutions(
            @PathVariable Long taskId,
            @PathVariable Long executionId,
            @RequestBody(required = false) SyncObjectRetryRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        return PlatformApiResponse.success(dataSyncService.retryObjectExecutions(
                taskId, executionId, request, actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    /**
     * 查询某个同步任务的 checkpoint。
     *
     * <p>checkpoint 是恢复和回放的关键依据；即使当前还没有 worker 写入，API 先稳定下来也便于后续执行器对接。
     */
    @GetMapping("/checkpoints")
    public PlatformApiResponse<PlatformPageResponse<SyncCheckpoint>> pageCheckpoints(
            @PathVariable Long taskId,
            @RequestParam(required = false) Long executionId,
            @RequestParam(required = false) String checkpointType,
            @RequestParam(defaultValue = "1") Long current,
            @RequestParam(defaultValue = "20") Long size,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        SyncCheckpointQueryCriteria criteria = new SyncCheckpointQueryCriteria(taskId, executionId, checkpointType, current, size);
        return PlatformApiResponse.success(dataSyncService.pageCheckpoints(
                criteria, actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    /**
     * 查询某个同步任务的错误样本。
     */
    @GetMapping("/errors")
    public PlatformApiResponse<PlatformPageResponse<SyncErrorSample>> pageErrorSamples(
            @PathVariable Long taskId,
            @RequestParam(required = false) Long executionId,
            @RequestParam(required = false) String errorType,
            @RequestParam(required = false) Boolean retryable,
            @RequestParam(defaultValue = "1") Long current,
            @RequestParam(defaultValue = "20") Long size,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        SyncErrorSampleQueryCriteria criteria = new SyncErrorSampleQueryCriteria(taskId, executionId, errorType, retryable, current, size);
        return PlatformApiResponse.success(dataSyncService.pageErrorSamples(
                criteria, actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    /**
     * 查询某个同步任务的审计记录。
     */
    @GetMapping("/audit")
    public PlatformApiResponse<PlatformPageResponse<SyncAuditRecord>> pageAuditRecords(
            @PathVariable Long taskId,
            @RequestParam(required = false) Long executionId,
            @RequestParam(required = false) String actionType,
            @RequestParam(required = false) Long actorIdFilter,
            @RequestParam(defaultValue = "1") Long current,
            @RequestParam(defaultValue = "20") Long size,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        SyncAuditQueryCriteria criteria = new SyncAuditQueryCriteria(taskId, executionId, actionType, actorIdFilter, current, size);
        return PlatformApiResponse.success(dataSyncService.pageAuditRecords(
                criteria, actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    private SyncActorContext actorContext(Long tenantId, Long actorId, String actorRole, String traceId, HttpHeaders headers) {
        return SyncActorContextHeaderSupport.fromHeaders(tenantId, actorId, actorRole, traceId, headers);
    }
}
