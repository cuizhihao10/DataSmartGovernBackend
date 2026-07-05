/**
 * @Author : Cui
 * @Date: 2026/05/08 09:13
 * @Description DataSmart Govern Backend - DataSyncExecutorCallbackController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller;

import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionCheckpointRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionCompleteRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionFailRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionStartRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncOfflineRunnerReportRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncOfflineRunnerReportResult;
import com.czh.datasmart.govern.datasync.entity.SyncCheckpoint;
import com.czh.datasmart.govern.datasync.entity.SyncErrorSample;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.service.DataSyncService;
import com.czh.datasmart.govern.datasync.service.support.DataSyncExecutorServiceAccountSignatureSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncOfflineRunnerReportCallbackService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据同步执行器回调 API。
 *
 * <p>该控制器面向后续 data-sync worker 或 task-management worker。
 * 它和用户侧的 DataSyncTaskController 分开，是为了明确“人类/管理端动作”和“执行器协议动作”的边界。
 *
 * <p>当前先实现 start、checkpoint、complete、fail 四类回调。
 * 这些接口都属于机器协议入口：它们不是普通用户在页面上点击按钮触发的管理动作，
 * 而是 worker 在真实搬数过程中持续上报执行状态、断点和失败样本。
 * 因此除了业务层的状态机、幂等和租户校验以外，控制器入口还会先校验服务账号签名，
 * 防止内网任意进程伪造 actorRole 后直接把 execution 标记为成功或失败。
 */
@RestController
@RequestMapping("/sync-tasks/{taskId}/executions/{executionId}")
@RequiredArgsConstructor
public class DataSyncExecutorCallbackController {

    private final DataSyncService dataSyncService;
    private final DataSyncExecutorServiceAccountSignatureSupport signatureSupport;
    private final SyncOfflineRunnerReportCallbackService offlineRunnerReportCallbackService;

    /**
     * 执行器开始处理 execution。
     *
     * <p>业务含义：worker 已经拿到租约并准备实际读取源端、写入目标端。
     * start 之后 execution 会进入 RUNNING 语义，后续只能通过 checkpoint、complete、fail 等协议继续推进。
     */
    @PostMapping("/start")
    public PlatformApiResponse<SyncExecution> startExecution(
            @PathVariable Long taskId,
            @PathVariable Long executionId,
            @Valid @RequestBody SyncExecutionStartRequest request,
            HttpServletRequest servletRequest,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        signatureSupport.verify(servletRequest, traceId, "START");
        return PlatformApiResponse.success("同步执行已开始",
                dataSyncService.startExecution(taskId, executionId, request, actorContext(tenantId, actorId, actorRole, traceId)), traceId);
    }

    /**
     * 执行器写入 checkpoint。
     *
     * <p>checkpoint 是断点续行能力的核心数据。高吞吐同步任务可能每批、每分片或每个 binlog 位点写一次 checkpoint；
     * 如果该接口缺少签名保护，攻击者可以伪造断点，导致恢复时跳过数据或重复写入。
     */
    @PostMapping("/checkpoints")
    public PlatformApiResponse<SyncCheckpoint> writeCheckpoint(
            @PathVariable Long taskId,
            @PathVariable Long executionId,
            @Valid @RequestBody SyncExecutionCheckpointRequest request,
            HttpServletRequest servletRequest,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        signatureSupport.verify(servletRequest, traceId, "CHECKPOINT");
        return PlatformApiResponse.success("同步 checkpoint 已写入",
                dataSyncService.writeCheckpoint(taskId, executionId, request, actorContext(tenantId, actorId, actorRole, traceId)), traceId);
    }

    /**
     * 执行器标记 execution 成功。
     *
     * <p>complete 是执行链路的终态动作之一，会影响任务状态、审计、后续调度和质量校验触发。
     * 因此它必须先通过机器身份校验，再由 service 层判断当前 execution 是否处于可完成状态。
     */
    @PostMapping("/complete")
    public PlatformApiResponse<SyncExecution> completeExecution(
            @PathVariable Long taskId,
            @PathVariable Long executionId,
            @Valid @RequestBody SyncExecutionCompleteRequest request,
            HttpServletRequest servletRequest,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        signatureSupport.verify(servletRequest, traceId, "COMPLETE");
        return PlatformApiResponse.success("同步执行已完成",
                dataSyncService.completeExecution(taskId, executionId, request, actorContext(tenantId, actorId, actorRole, traceId)), traceId);
    }

    /**
     * 执行器标记 execution 失败，并写入错误样本。
     *
     * <p>fail 不只是简单改状态，还会把错误摘要、样本和后续人工介入信号写入平台。
     * 签名校验可以避免恶意调用者伪造失败，把正常任务推入告警或人工处理流程。
     */
    @PostMapping("/fail")
    public PlatformApiResponse<SyncErrorSample> failExecution(
            @PathVariable Long taskId,
            @PathVariable Long executionId,
            @Valid @RequestBody SyncExecutionFailRequest request,
            HttpServletRequest servletRequest,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        signatureSupport.verify(servletRequest, traceId, "FAIL");
        return PlatformApiResponse.success("同步执行已失败并记录错误样本",
                dataSyncService.failExecution(taskId, executionId, request, actorContext(tenantId, actorId, actorRole, traceId)), traceId);
    }

    /**
     * 专用离线 Runner 低敏执行报告回调。
     *
     * <p>该接口是 DataX-style 专用 Runner adapter 的后半段闭环：worker loop 把合同派发给专用 Runner 后，
     * Runner 可以通过本接口回传 QUEUED、RUNNING、CHECKPOINT、SUCCEEDED、FAILED 等结构化状态。
     * 与普通 checkpoint/complete/fail 的区别是，本接口的请求体只允许携带低敏报告事实：
     * 计数、状态码、checkpointRef/checkpointDigest、低敏错误码和低敏 issueCodes。</p>
     *
     * <p>为什么仍放在 execution callback controller 下：
     * 1. 它仍然是机器协议入口，必须复用服务账号签名校验；
     * 2. 它仍然围绕 taskId/executionId 推进 data-sync 生命周期；
     * 3. gateway 和 permission-admin 已经用 data-sync execution callback 通配策略统一保护执行器回调，
     *    新增子路径不会额外扩大人类用户权限面。</p>
     */
    @PostMapping("/offline-runner/reports")
    public PlatformApiResponse<SyncOfflineRunnerReportResult> reportOfflineRunnerExecution(
            @PathVariable Long taskId,
            @PathVariable Long executionId,
            @Valid @RequestBody SyncOfflineRunnerReportRequest request,
            HttpServletRequest servletRequest,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        signatureSupport.verify(servletRequest, traceId, "OFFLINE_RUNNER_REPORT");
        return PlatformApiResponse.success("离线 Runner 执行报告已处理",
                offlineRunnerReportCallbackService.applyReport(taskId, executionId, request,
                        actorContext(tenantId, actorId, actorRole, traceId)), traceId);
    }

    private SyncActorContext actorContext(Long tenantId, Long actorId, String actorRole, String traceId) {
        return new SyncActorContext(tenantId, actorId, actorRole, traceId);
    }
}
