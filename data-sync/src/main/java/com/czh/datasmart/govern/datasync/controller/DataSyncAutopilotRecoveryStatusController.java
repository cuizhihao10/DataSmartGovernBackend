/**
 * @Author : Cui
 * @Date: 2026/08/12
 * @Description DataSmart Govern Backend - DataSyncAutopilotRecoveryStatusController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller;

import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.support.SyncActorContextHeaderSupport;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.service.DataSyncService;
import com.czh.datasmart.govern.datasync.service.support.SyncAutopilotRecoveryStatusQueryService;
import com.czh.datasmart.govern.datasync.service.support.SyncAutopilotRecoveryStatusView;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public read-only API for observing unattended recovery from a sync execution detail page.
 *
 * <p>This controller is intentionally separate from the service-internal recovery mutation controller. Browser
 * callers may only inspect low-sensitive state here; they cannot submit a model decision, apply quarantine,
 * advance a case, acknowledge a Kafka trigger, or retry an execution through this route.</p>
 */
@RestController
@RequestMapping("/sync-tasks")
@RequiredArgsConstructor
public class DataSyncAutopilotRecoveryStatusController {

    private final DataSyncService dataSyncService;
    private final SyncAutopilotRecoveryStatusQueryService statusQueryService;

    /**
     * Returns the latest governed Autopilot lifecycle facts for one visible task execution.
     *
     * <p>The normal task service runs first so tenant, project, authorized-project, and SELF visibility are
     * identical to the task detail page. The query service then performs a second execution-to-task ownership
     * check before loading recovery tables. The method is read-only and has no recovery side effect.</p>
     *
     * @param taskId visible data-sync task
     * @param executionId execution under that task
     * @param tenantId gateway-authenticated tenant identity
     * @param actorId gateway-authenticated represented user
     * @param actorRole gateway-authenticated role code
     * @param traceId cross-service trace identifier
     * @param headers complete trusted platform context, including project scope
     * @return standard API envelope containing the restricted recovery projection
     */
    @GetMapping("/{taskId}/executions/{executionId}/autopilot-recovery")
    public PlatformApiResponse<SyncAutopilotRecoveryStatusView> getAutopilotRecoveryStatus(
            @PathVariable Long taskId,
            @PathVariable Long executionId,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        SyncActorContext actorContext = SyncActorContextHeaderSupport.fromHeaders(
                tenantId, actorId, actorRole, traceId, headers);
        SyncTask visibleTask = dataSyncService.getTask(taskId, actorContext);
        return PlatformApiResponse.success(statusQueryService.query(visibleTask, executionId), traceId);
    }
}
