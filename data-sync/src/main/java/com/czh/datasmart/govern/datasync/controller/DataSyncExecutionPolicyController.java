/**
 * @Author : Cui
 * @Date: 2026/07/09 22:40
 * @Description DataSmart Govern Backend - DataSyncExecutionPolicyController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller;

import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.api.PlatformPageResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionPolicyQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionPolicySnapshotView;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionPolicyUpsertRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionPolicyView;
import com.czh.datasmart.govern.datasync.controller.support.SyncActorContextHeaderSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncExecutionPolicyService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据同步执行策略 API。
 *
 * <p>该控制器对应管理员“执行策略”配置页和任务运行详情中的“本次策略快照”。执行策略属于运维治理面，
 * 与普通新建同步任务向导解耦：用户不再手填分片数、channel、重试、超时，而由管理员策略和运行时自动规划决定。</p>
 */
@RestController
@RequiredArgsConstructor
public class DataSyncExecutionPolicyController {

    private final SyncExecutionPolicyService executionPolicyService;

    /**
     * 查询管理员执行策略列表。
     *
     * <p>支持按 scopeType、connectorType、datasourceId、syncTaskId 等条件过滤。返回值不包含连接串、SQL 或任务配置正文。</p>
     */
    @GetMapping("/sync-execution-policies")
    public PlatformApiResponse<PlatformPageResponse<SyncExecutionPolicyView>> pagePolicies(
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String scopeType,
            @RequestParam(required = false) String scopeKey,
            @RequestParam(required = false) Long datasourceId,
            @RequestParam(required = false) String connectorType,
            @RequestParam(required = false) String connectorRole,
            @RequestParam(required = false) Long syncTaskId,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(defaultValue = "1") Long current,
            @RequestParam(defaultValue = "20") Long size,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long headerTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        SyncExecutionPolicyQueryCriteria criteria = new SyncExecutionPolicyQueryCriteria(
                tenantId, projectId, scopeType, scopeKey, datasourceId, connectorType, connectorRole,
                syncTaskId, enabled, current, size);
        return PlatformApiResponse.success(executionPolicyService.pagePolicies(
                criteria, actorContext(headerTenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    /**
     * 创建执行策略。
     *
     * <p>创建时如果 scopeKey 或 policyCode 为空，服务端会按作用域生成稳定默认值，便于导入导出和幂等更新。</p>
     */
    @PostMapping("/sync-execution-policies")
    public PlatformApiResponse<SyncExecutionPolicyView> createPolicy(
            @RequestBody SyncExecutionPolicyUpsertRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        if (request != null) {
            request.setId(null);
        }
        return PlatformApiResponse.success(executionPolicyService.upsertPolicy(
                request, actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    /**
     * 更新执行策略。
     *
     * <p>更新动作只修改策略本身，不会改写已经完成的 execution 策略快照。</p>
     */
    @PutMapping("/sync-execution-policies/{id}")
    public PlatformApiResponse<SyncExecutionPolicyView> updatePolicy(
            @PathVariable Long id,
            @RequestBody SyncExecutionPolicyUpsertRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        if (request != null) {
            request.setId(id);
        }
        return PlatformApiResponse.success(executionPolicyService.upsertPolicy(
                request, actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    /**
     * 禁用执行策略。
     *
     * <p>这里使用软禁用而不是物理删除，保证策略变更可审计、可回滚。</p>
     */
    @DeleteMapping("/sync-execution-policies/{id}")
    public PlatformApiResponse<Void> disablePolicy(
            @PathVariable Long id,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        executionPolicyService.disablePolicy(id, actorContext(tenantId, actorId, actorRole, traceId, headers));
        return PlatformApiResponse.success(null, traceId);
    }

    /**
     * 查询某次 execution 的策略快照。
     *
     * <p>任务运行详情页使用该接口解释本次执行的 channel、批大小、超时、重试和自动分片结果。</p>
     */
    @GetMapping("/sync-tasks/{taskId}/executions/{executionId}/policy-snapshot")
    public PlatformApiResponse<SyncExecutionPolicySnapshotView> getExecutionPolicySnapshot(
            @PathVariable Long taskId,
            @PathVariable Long executionId,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader HttpHeaders headers) {
        return PlatformApiResponse.success(executionPolicyService.getSnapshot(
                taskId, executionId, actorContext(tenantId, actorId, actorRole, traceId, headers)), traceId);
    }

    private SyncActorContext actorContext(Long tenantId, Long actorId, String actorRole, String traceId, HttpHeaders headers) {
        return SyncActorContextHeaderSupport.fromHeaders(tenantId, actorId, actorRole, traceId, headers);
    }
}
