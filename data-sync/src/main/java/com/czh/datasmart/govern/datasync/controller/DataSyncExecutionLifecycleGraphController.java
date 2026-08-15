/**
 * @Author : Cui
 * @Date: 2026/08/15
 * @Description DataSmart Govern Backend - DataSyncExecutionLifecycleGraphController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller;

import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.support.SyncActorContextHeaderSupport;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.service.DataSyncService;
import com.czh.datasmart.govern.datasync.service.support.SyncExecutionLifecycleGraphService;
import com.czh.datasmart.govern.datasync.service.support.SyncExecutionLifecycleGraphView;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 同步 execution 统一全链路状态图只读接口。
 *
 * <p>浏览器只能读取已经通过任务数据范围校验的低敏投影，不能通过该接口推进 Agent、Kafka、worker 或 Recovery
 * 状态。Controller 先复用同步任务详情的可见性判断，再把同一个可信任务聚合交给图服务。</p>
 */
@RestController
@RequestMapping("/sync-tasks")
@RequiredArgsConstructor
public class DataSyncExecutionLifecycleGraphController {

    private final DataSyncService dataSyncService;
    private final SyncExecutionLifecycleGraphService lifecycleGraphService;

    /**
     * 查询“用户目标到最终验证”的全链路状态图。
     *
     * @param taskId 同步任务 ID
     * @param executionId 根 execution ID
     * @param tenantId Gateway 注入的租户 ID
     * @param actorId Gateway 注入的用户 ID
     * @param actorRole Gateway 注入的用户角色
     * @param traceId 链路追踪 ID
     * @param headers 完整可信上下文 Header
     * @return 标准响应包裹的低敏全链路状态图
     */
    @GetMapping("/{taskId}/executions/{executionId}/lifecycle-graph")
    public PlatformApiResponse<SyncExecutionLifecycleGraphView> getLifecycleGraph(
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
        return PlatformApiResponse.success(
                lifecycleGraphService.query(visibleTask, executionId, actorContext), traceId);
    }
}
