/**
 * @Author : Cui
 * @Date: 2026/07/07 23:15
 * @Description DataSmart Govern Backend - DataSyncTaskSchedulerController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller;

import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskScheduleDispatchRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskScheduleDispatchResult;
import com.czh.datasmart.govern.datasync.service.DataSyncTaskScheduleService;
import com.czh.datasmart.govern.datasync.service.support.DataSyncExecutorServiceAccountSignatureSupport;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * data-sync 任务级调度器运维与 internal 触发入口。
 *
 * <p>该 Controller 暴露两个等价前缀：</p>
 * <p>1. {@code /sync-task-schedulers/dispatch-due}：便于本地开发、运维台或测试脚本手动触发一轮调度；</p>
 * <p>2. {@code /internal/sync-task-schedulers/dispatch-due}：便于未来调度中心、平台控制面或独立 worker 服务调用。</p>
 *
 * <p>安全说明：本接口会创建 execution，虽然不直接读取源库或写目标库，但它会推动后续 worker loop 执行真实数据搬运。
 * 因此仍复用执行器服务账号签名校验能力，生产环境建议开启 HMAC/mTLS/OIDC service account 或 service mesh 策略。</p>
 */
@RestController
@RequestMapping({"/sync-task-schedulers", "/internal/sync-task-schedulers"})
@RequiredArgsConstructor
public class DataSyncTaskSchedulerController {

    private final DataSyncTaskScheduleService scheduleService;
    private final DataSyncExecutorServiceAccountSignatureSupport signatureSupport;

    /**
     * 手动或 internal 触发一轮到期任务派发。
     *
     * <p>返回结果是低敏摘要，只包含计数、任务调度问题码和 executionId 列表，不返回 scheduleConfig 原文、
     * SQL、字段映射、过滤条件、连接地址或远端执行响应。</p>
     */
    @PostMapping("/dispatch-due")
    public PlatformApiResponse<SyncTaskScheduleDispatchResult> dispatchDueTasks(
            @RequestBody(required = false) SyncTaskScheduleDispatchRequest request,
            HttpServletRequest servletRequest,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        signatureSupport.verify(servletRequest, traceId, "TASK_SCHEDULER_DISPATCH_DUE");
        SyncActorContext actorContext = new SyncActorContext(tenantId, actorId, actorRole, traceId);
        return PlatformApiResponse.success("data-sync 到期定时任务派发完成",
                scheduleService.dispatchDueTasks(request, actorContext), traceId);
    }
}
