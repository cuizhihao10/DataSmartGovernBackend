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
import com.czh.datasmart.govern.datasync.controller.dto.SyncDirtyRecordReplayRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncDirtyRecordReplayResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncDirtyRecordQuarantineRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncDirtyRecordQuarantineResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncRecoveryCasePublishRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncRecoveryCasePublishResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncErrorSampleQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionLogQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionDiagnosisResponse;
import com.czh.datasmart.govern.datasync.controller.dto.SyncObjectExecutionQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncObjectExecutionView;
import com.czh.datasmart.govern.datasync.controller.dto.SyncObjectRetryRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncObjectRetryResult;
import com.czh.datasmart.govern.datasync.controller.support.SyncActorContextHeaderSupport;
import com.czh.datasmart.govern.datasync.entity.SyncAuditRecord;
import com.czh.datasmart.govern.datasync.entity.SyncCheckpoint;
import com.czh.datasmart.govern.datasync.entity.SyncErrorSample;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncExecutionLog;
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
     * 查询某次 execution 的运行日志。
     *
     * <p>路由语义：</p>
     * <p>1. {@code taskId} 用于先锁定同步任务和权限范围；</p>
     * <p>2. {@code executionId} 用于锁定某一次真实运行；</p>
     * <p>3. {@code logs} 表示该运行内部按时间排序的阶段事件，例如入队、认领、计划生成、创建通道、批次同步、checkpoint 和终态。</p>
     *
     * <p>与普通应用日志不同，本接口返回的是低敏产品日志：可以展示给任务负责人、运营人员和 Agent，
     * 但不会返回 SQL 正文、连接串、凭据、样本行、where 原文或真实分片边界。</p>
     */
    @GetMapping("/executions/{executionId}/logs")
    public PlatformApiResponse<PlatformPageResponse<SyncExecutionLog>> pageExecutionLogs(
            @PathVariable Long taskId,
            @PathVariable Long executionId,
            @RequestParam(required = false) String logStage,
            @RequestParam(required = false) String logLevel,
            @RequestParam(defaultValue = "1") Long current,
            @RequestParam(defaultValue = "100") Long size,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        SyncExecutionLogQueryCriteria criteria = new SyncExecutionLogQueryCriteria(
                taskId, executionId, logStage, logLevel, current, size);
        return PlatformApiResponse.success(dataSyncService.pageExecutionLogs(
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
     * Aggregate the latest real execution ledger into a bounded Agent diagnosis package.
     * The response intentionally excludes credentials, SQL, source keys and row samples.
     */
    @GetMapping("/agent-diagnosis")
    public PlatformApiResponse<SyncExecutionDiagnosisResponse> diagnoseExecution(
            @PathVariable Long taskId,
            @RequestParam(required = false) Long executionId,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        return PlatformApiResponse.success(dataSyncService.diagnoseExecution(
                taskId, executionId, actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    /** Preview reversible dirty-record quarantine and return a confirmation digest. */
    @PostMapping("/errors/quarantine/preview")
    public PlatformApiResponse<SyncDirtyRecordQuarantineResult> previewDirtyRecordQuarantine(
            @PathVariable Long taskId,
            @RequestBody SyncDirtyRecordQuarantineRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        return PlatformApiResponse.success(dataSyncService.previewDirtyRecordQuarantine(
                taskId, request, actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    /** Apply the exact preview after explicit confirmation; source rows are never physically deleted. */
    @PostMapping("/errors/quarantine/apply")
    public PlatformApiResponse<SyncDirtyRecordQuarantineResult> applyDirtyRecordQuarantine(
            @PathVariable Long taskId,
            @RequestBody SyncDirtyRecordQuarantineRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        return PlatformApiResponse.success(dataSyncService.applyDirtyRecordQuarantine(
                taskId, request, actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    /** Persist a reusable case only after the repaired execution has succeeded. */
    @PostMapping("/agent-recovery-cases")
    public PlatformApiResponse<SyncRecoveryCasePublishResult> publishRecoveryCase(
            @PathVariable Long taskId,
            @RequestBody SyncRecoveryCasePublishRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        return PlatformApiResponse.success(dataSyncService.publishRecoveryCase(
                taskId, request, actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    /**
     * 基于错误样本发起脏数据修复重放。
     *
     * <p>路由语义：</p>
     * <p>1. {@code taskId} 表示当前修复动作归属的同步任务，服务层会先按任务做租户、项目、SELF 数据范围校验；</p>
     * <p>2. 请求体中的 {@code executionId} 表示错误样本来源 execution，服务端会校验它确实属于该任务；</p>
     * <p>3. {@code errorSampleIds} 表示精确重放一批错误样本；如果想重放全部可重试样本，必须显式传
     * {@code replayAllRetryableInExecution=true}；</p>
     * <p>4. {@code repairConfirmed=true} 是安全闸门，用来表达操作者已经修复字段映射、目标约束、重复主键、
     * 数据格式等根因，不允许“还没修就盲目重跑”。</p>
     *
     * <p>权限边界：该接口应由 gateway/permission-admin 标记为 {@code SYNC_TASK + REPLAY_DIRTY_RECORDS}。
     * 普通查询错误样本是 VIEW，修复重放会创建新的 replay execution，属于写操作和高影响恢复动作，必须单独授权和审计。</p>
     *
     * <p>低敏边界：响应只返回新 executionId、recoveryPlanId、样本数量和 selector 模式，不返回错误样本原文、
     * SQL、连接串、凭据、where 条件、字段映射正文或 worker 内部参数。</p>
     */
    @PostMapping("/errors/replay")
    public PlatformApiResponse<SyncDirtyRecordReplayResult> replayDirtyRecords(
            @PathVariable Long taskId,
            @RequestBody(required = false) SyncDirtyRecordReplayRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        return PlatformApiResponse.success(dataSyncService.replayDirtyRecords(
                taskId, request, actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
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
