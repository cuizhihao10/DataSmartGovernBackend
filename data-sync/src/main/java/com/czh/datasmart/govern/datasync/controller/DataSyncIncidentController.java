/**
 * @Author : Cui
 * @Date: 2026/05/08 22:48
 * @Description DataSmart Govern Backend - DataSyncIncidentController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller;

import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.api.PlatformPageResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncIncidentOperationRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncIncidentOperationResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncIncidentQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.support.SyncActorContextHeaderSupport;
import com.czh.datasmart.govern.datasync.entity.SyncIncidentRecord;
import com.czh.datasmart.govern.datasync.service.DataSyncIncidentService;
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
 * 同步事故记录 API。
 *
 * <p>事故 API 面向运营台和平台管理员：
 * 1. 列表和详情用于排查、筛选、报表和事故复盘；
 * 2. acknowledge 表示事故已被接手；
 * 3. assign 表示事故负责人发生变化；
 * 4. resolve 表示问题已解决但可能还需要关闭确认；
 * 5. close 表示事故生命周期终结，可进入 SLA 和历史统计。
 *
 * <p>当前 Controller 只负责 HTTP 协议和上下文提取，权限、租户范围、状态机和审计都放到 Service。
 */
@RestController
@RequestMapping("/sync-incidents")
@RequiredArgsConstructor
public class DataSyncIncidentController {

    private final DataSyncIncidentService incidentService;

    /**
     * 分页查询同步事故。
     */
    @GetMapping
    public PlatformApiResponse<PlatformPageResponse<SyncIncidentRecord>> pageIncidents(
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long syncTaskId,
            @RequestParam(required = false) Long executionId,
            @RequestParam(required = false) String incidentType,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String incidentStatus,
            @RequestParam(required = false) Long operatorId,
            @RequestParam(required = false) Long assignedOperatorId,
            @RequestParam(defaultValue = "1") Long current,
            @RequestParam(defaultValue = "20") Long size,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        SyncIncidentQueryCriteria criteria = new SyncIncidentQueryCriteria(
                tenantId, projectId, null, syncTaskId, executionId, incidentType, severity, incidentStatus,
                operatorId, assignedOperatorId, current, size);
        return PlatformApiResponse.success(incidentService.pageIncidents(
                criteria, actorContext(actorTenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    /**
     * 查询同步事故详情。
     */
    @GetMapping("/{incidentId}")
    public PlatformApiResponse<SyncIncidentRecord> getIncident(
            @PathVariable Long incidentId,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        return PlatformApiResponse.success(incidentService.getIncident(
                incidentId, actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    /**
     * 确认事故已被接手。
     */
    @PostMapping("/{incidentId}/acknowledge")
    public PlatformApiResponse<SyncIncidentOperationResult> acknowledge(
            @PathVariable Long incidentId,
            @Valid @RequestBody(required = false) SyncIncidentOperationRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        return PlatformApiResponse.success("事故已确认接手",
                incidentService.acknowledge(incidentId, request, actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    /**
     * 分派事故负责人。
     */
    @PostMapping("/{incidentId}/assign")
    public PlatformApiResponse<SyncIncidentOperationResult> assign(
            @PathVariable Long incidentId,
            @Valid @RequestBody SyncIncidentOperationRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        return PlatformApiResponse.success("事故负责人已更新",
                incidentService.assign(incidentId, request, actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    /**
     * 标记事故已解决。
     */
    @PostMapping("/{incidentId}/resolve")
    public PlatformApiResponse<SyncIncidentOperationResult> resolve(
            @PathVariable Long incidentId,
            @Valid @RequestBody(required = false) SyncIncidentOperationRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        return PlatformApiResponse.success("事故已标记解决",
                incidentService.resolve(incidentId, request, actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    /**
     * 关闭事故。
     */
    @PostMapping("/{incidentId}/close")
    public PlatformApiResponse<SyncIncidentOperationResult> close(
            @PathVariable Long incidentId,
            @Valid @RequestBody(required = false) SyncIncidentOperationRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        return PlatformApiResponse.success("事故已关闭",
                incidentService.close(incidentId, request, actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    private SyncActorContext actorContext(Long tenantId, Long actorId, String actorRole, String traceId, HttpHeaders headers) {
        return SyncActorContextHeaderSupport.fromHeaders(tenantId, actorId, actorRole, traceId, headers);
    }
}
