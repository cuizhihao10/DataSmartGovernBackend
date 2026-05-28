/**
 * @Author : Cui
 * @Date: 2026/05/08 22:31
 * @Description DataSmart Govern Backend - DataSyncAttentionOperationController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller;

import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncAttentionOperationRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncAttentionOperationResult;
import com.czh.datasmart.govern.datasync.service.DataSyncAttentionOperationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 同步任务人工介入运营 API。
 *
 * <p>当任务进入 `AWAITING_OPERATOR_ACTION` 后，说明自动化执行已经到达边界。
 * 这个控制器提供运营人员接手处理的动作入口，而不是继续让普通执行器无限重试。
 *
 * <p>路由设计说明：
 * 1. `/sync-tasks/{taskId}/attention/*` 表示动作围绕某个同步任务的人工介入状态展开；
 * 2. acknowledge/resolve/rerun/cancel/archive/create-incident 分别对应确认、解决、重跑、取消、归档、创建事故；
 * 3. 当前权限在 service 内做本地兜底，后续应由 gateway + permission-admin 前置拦截。
 */
@RestController
@RequestMapping("/sync-tasks/{taskId}/attention")
@RequiredArgsConstructor
public class DataSyncAttentionOperationController {

    private final DataSyncAttentionOperationService attentionOperationService;

    /**
     * 确认人工介入任务已被运营人员接手。
     */
    @PostMapping("/acknowledge")
    public PlatformApiResponse<SyncAttentionOperationResult> acknowledge(
            @PathVariable Long taskId,
            @Valid @RequestBody(required = false) SyncAttentionOperationRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success("人工介入任务已确认",
                attentionOperationService.acknowledge(taskId, request, actorContext(tenantId, actorId, actorRole, traceId)), traceId);
    }

    /**
     * 标记人工介入问题已解决，任务回到 CONFIGURED。
     */
    @PostMapping("/resolve")
    public PlatformApiResponse<SyncAttentionOperationResult> resolve(
            @PathVariable Long taskId,
            @Valid @RequestBody(required = false) SyncAttentionOperationRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success("人工介入问题已解决",
                attentionOperationService.resolve(taskId, request, actorContext(tenantId, actorId, actorRole, traceId)), traceId);
    }

    /**
     * 人工处理后立即创建新的 execution 并重新入队。
     */
    @PostMapping("/rerun")
    public PlatformApiResponse<SyncAttentionOperationResult> rerun(
            @PathVariable Long taskId,
            @Valid @RequestBody(required = false) SyncAttentionOperationRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success("人工介入任务已重新入队",
                attentionOperationService.rerun(taskId, request, actorContext(tenantId, actorId, actorRole, traceId)), traceId);
    }

    /**
     * 取消人工介入任务。
     */
    @PostMapping("/cancel")
    public PlatformApiResponse<SyncAttentionOperationResult> cancel(
            @PathVariable Long taskId,
            @Valid @RequestBody(required = false) SyncAttentionOperationRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success("人工介入任务已取消",
                attentionOperationService.cancel(taskId, request, actorContext(tenantId, actorId, actorRole, traceId)), traceId);
    }

    /**
     * 归档人工介入任务。
     */
    @PostMapping("/archive")
    public PlatformApiResponse<SyncAttentionOperationResult> archive(
            @PathVariable Long taskId,
            @Valid @RequestBody(required = false) SyncAttentionOperationRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success("人工介入任务已归档",
                attentionOperationService.archive(taskId, request, actorContext(tenantId, actorId, actorRole, traceId)), traceId);
    }

    /**
     * 创建事故记录，保留任务的人工介入状态。
     */
    @PostMapping("/incidents")
    public PlatformApiResponse<SyncAttentionOperationResult> createIncident(
            @PathVariable Long taskId,
            @Valid @RequestBody(required = false) SyncAttentionOperationRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success("人工介入任务已创建事故记录",
                attentionOperationService.createIncident(taskId, request, actorContext(tenantId, actorId, actorRole, traceId)), traceId);
    }

    private SyncActorContext actorContext(Long tenantId, Long actorId, String actorRole, String traceId) {
        return new SyncActorContext(tenantId, actorId, actorRole, traceId);
    }
}
