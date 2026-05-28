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
