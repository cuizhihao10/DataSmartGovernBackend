/**
 * @Author : Cui
 * @Date: 2026/06/27 16:20
 * @Description DataSmart Govern Backend - DataSyncRecoveryPlanWorkerController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller;

import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncRecoveryPlanWorkerRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncRecoveryPlanWorkerResult;
import com.czh.datasmart.govern.datasync.service.DataSyncRecoveryPlanWorkerService;
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
 * 同步恢复计划 worker 协议 API。
 *
 * <p>该控制器面向受信任的 data-sync worker，而不是面向普通用户或后台管理页面。
 * replay/backfill 的控制面入口位于 {@code /sync-tasks/{id}/replay} 和 {@code /sync-tasks/{id}/backfill}；
 * 那些入口负责“由谁发起恢复、是否有权限、创建哪条恢复计划”。本控制器负责的是下一段链路：
 * worker 认领 execution 后，如何安全读取并确认消费恢复计划。
 *
 * <p>为什么不把这两个路由放进 {@link DataSyncExecutorLeaseController}：
 * 1. 租约控制器表达“谁持有 execution 执行权”，恢复计划控制器表达“恢复类 execution 应如何执行”；
 * 2. 两者虽然都属于 worker 机器协议，但状态机不同，拆开后更便于学习和维护；
 * 3. 用户已经明确要求降低耦合和控制单文件行数，独立 controller 可以避免租约控制器继续膨胀。
 *
 * <p>安全边界：
 * 1. 每个路由都会先执行 HMAC 服务账号签名校验；
 * 2. service 层还会校验 request.executorId 必须等于 execution.executorId；
 * 3. 响应只返回低敏恢复坐标，不返回 SQL、连接串、凭据、样本数据、prompt、模型输出或工具原始参数。
 */
@RestController
@RequestMapping("/sync-executions")
@RequiredArgsConstructor
public class DataSyncRecoveryPlanWorkerController {

    private final DataSyncRecoveryPlanWorkerService recoveryPlanWorkerService;
    private final DataSyncExecutorServiceAccountSignatureSupport signatureSupport;

    /**
     * worker 认领并读取恢复计划。
     *
     * <p>路由语义：
     * - path 中的 executionId 表示 worker 已经通过租约认领的执行记录；
     * - request.executorId 表示当前 worker 实例，必须与 execution.executorId 匹配；
     * - 返回值 hasRecoveryPlan=false 时，表示该 execution 是普通同步执行，worker 可继续普通路径；
     * - 返回值 hasRecoveryPlan=true 时，worker 应根据 recoveryType、sourceCheckpointId、windowStart 等低敏坐标初始化执行上下文。
     *
     * <p>状态流转：
     * CREATED -> CLAIMED 表示恢复计划已经送达 worker；
     * 如果重复调用时计划已经是 CLAIMED 或 CONSUMED，则按幂等成功返回当前计划状态。
     */
    @PostMapping("/{executionId}/recovery-plan/claim")
    public PlatformApiResponse<SyncRecoveryPlanWorkerResult> claimRecoveryPlan(
            @PathVariable Long executionId,
            @Valid @RequestBody SyncRecoveryPlanWorkerRequest request,
            HttpServletRequest servletRequest,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        signatureSupport.verify(servletRequest, traceId, "CLAIM_RECOVERY_PLAN");
        return PlatformApiResponse.success("同步恢复计划认领完成",
                recoveryPlanWorkerService.claimPlan(executionId, request,
                        new SyncActorContext(tenantId, actorId, actorRole, traceId)), traceId);
    }

    /**
     * worker 确认已消费恢复计划。
     *
     * <p>consume 的业务含义不是“恢复执行成功”，而是“worker 已经把恢复计划加载为本地执行策略”。
     * 真正的数据执行结果仍然要通过 start、checkpoint、complete、fail 等已有回调报告。
     *
     * <p>状态流转：
     * CLAIMED -> CONSUMED 表示 worker 已将计划应用到执行上下文；
     * 如果重复调用时计划已经是 CONSUMED，则按幂等成功返回；
     * 如果计划仍是 CREATED，则说明 worker 跳过了 claim 步骤，服务端会拒绝，避免丢失“计划已送达”的审计证据。
     */
    @PostMapping("/{executionId}/recovery-plan/consume")
    public PlatformApiResponse<SyncRecoveryPlanWorkerResult> consumeRecoveryPlan(
            @PathVariable Long executionId,
            @Valid @RequestBody SyncRecoveryPlanWorkerRequest request,
            HttpServletRequest servletRequest,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        signatureSupport.verify(servletRequest, traceId, "CONSUME_RECOVERY_PLAN");
        return PlatformApiResponse.success("同步恢复计划消费确认完成",
                recoveryPlanWorkerService.consumePlan(executionId, request,
                        new SyncActorContext(tenantId, actorId, actorRole, traceId)), traceId);
    }
}
