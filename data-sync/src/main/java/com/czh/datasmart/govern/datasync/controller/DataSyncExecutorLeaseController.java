/**
 * @Author : Cui
 * @Date: 2026/05/08 21:55
 * @Description DataSmart Govern Backend - DataSyncExecutorLeaseController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller;

import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExpiredLeaseRecoveryRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExpiredLeaseRecoveryResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionClaimRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionClaimResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionDeferRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionHeartbeatRequest;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.service.DataSyncExecutorLeaseService;
import com.czh.datasmart.govern.datasync.service.support.DataSyncExecutorServiceAccountSignatureSupport;
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
 * 数据同步执行器租约 API。
 *
 * <p>该控制器提供 worker 主动拉取执行记录的入口：
 * 1. claim：认领下一条 QUEUED execution；
 * 2. heartbeat：续租并上报轻量进度；
 * 3. defer：因容量、配额、维护窗口等原因延迟回队列。
 *
 * <p>claim、heartbeat、defer 是典型机器协议动作，调用方应该是平台受信任的执行器服务账号。
 * 因此这些入口会先做 HMAC 签名校验，再进入租约业务逻辑。
 * recover-expired-leases 则属于运维或定时维护动作，仍走普通 actor / operator 权限模型，
 * 避免把人工恢复入口误绑定到某一个 worker 服务账号。
 */
@RestController
@RequestMapping("/sync-executions")
@RequiredArgsConstructor
public class DataSyncExecutorLeaseController {

    private final DataSyncExecutorLeaseService leaseService;
    private final DataSyncExecutorServiceAccountSignatureSupport signatureSupport;

    /**
     * 认领下一条可执行记录。
     *
     * <p>claim 是 worker 进入调度系统的第一步。签名校验保护的是“谁能从队列拿任务”，
     * service 层随后还会继续判断租户范围、任务状态、connector 类型和租约时间等业务约束。
     */
    @PostMapping("/claim")
    public PlatformApiResponse<SyncExecutionClaimResult> claimNext(
            @Valid @RequestBody SyncExecutionClaimRequest request,
            HttpServletRequest servletRequest,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        signatureSupport.verify(servletRequest, traceId, "CLAIM");
        return PlatformApiResponse.success("同步执行认领完成",
                leaseService.claimNext(request, actorContext(tenantId, actorId, actorRole, traceId)), traceId);
    }

    /**
     * 执行器心跳续租。
     *
     * <p>heartbeat 代表 worker 仍然存活并继续处理当前 execution。
     * 如果心跳被伪造，系统可能错误地认为任务仍在运行，导致过期租约无法恢复。
     */
    @PostMapping("/{executionId}/heartbeat")
    public PlatformApiResponse<SyncExecution> heartbeat(
            @PathVariable Long executionId,
            @Valid @RequestBody SyncExecutionHeartbeatRequest request,
            HttpServletRequest servletRequest,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        signatureSupport.verify(servletRequest, traceId, "HEARTBEAT");
        return PlatformApiResponse.success("同步执行心跳已续租",
                leaseService.heartbeat(executionId, request, actorContext(tenantId, actorId, actorRole, traceId)), traceId);
    }

    /**
     * 执行器延迟回队列。
     *
     * <p>defer 用于 worker 主动释放当前 execution，例如目标端限流、分片容量不足或维护窗口到来。
     * 该动作会改变队列顺序和退避次数，所以也必须验证机器身份。
     */
    @PostMapping("/{executionId}/defer")
    public PlatformApiResponse<SyncExecution> defer(
            @PathVariable Long executionId,
            @Valid @RequestBody SyncExecutionDeferRequest request,
            HttpServletRequest servletRequest,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        signatureSupport.verify(servletRequest, traceId, "DEFER");
        return PlatformApiResponse.success("同步执行已延迟回队列",
                leaseService.defer(executionId, request, actorContext(tenantId, actorId, actorRole, traceId)), traceId);
    }

    /**
     * 恢复过期租约。
     *
     * <p>该接口面向运营人员或后续定时任务，用于处理 worker 失联、进程崩溃、网络隔离后长期卡在 RUNNING 的 execution。
     */
    @PostMapping("/recover-expired-leases")
    public PlatformApiResponse<SyncExpiredLeaseRecoveryResult> recoverExpiredLeases(
            @RequestBody(required = false) SyncExpiredLeaseRecoveryRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success("同步执行过期租约恢复完成",
                leaseService.recoverExpiredLeases(request, actorContext(tenantId, actorId, actorRole, traceId)), traceId);
    }

    private SyncActorContext actorContext(Long tenantId, Long actorId, String actorRole, String traceId) {
        return new SyncActorContext(tenantId, actorId, actorRole, traceId);
    }
}
