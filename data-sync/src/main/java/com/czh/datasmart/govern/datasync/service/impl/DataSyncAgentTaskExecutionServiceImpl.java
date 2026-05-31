/**
 * @Author : Cui
 * @Date: 2026/05/31 23:20
 * @Description DataSmart Govern Backend - DataSyncAgentTaskExecutionServiceImpl.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.impl;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasync.controller.dto.AgentSyncTaskExecuteRequest;
import com.czh.datasmart.govern.datasync.controller.dto.AgentSyncTaskExecuteResponse;
import com.czh.datasmart.govern.datasync.controller.dto.CreateSyncTaskRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskOperationResult;
import com.czh.datasmart.govern.datasync.entity.SyncCallbackIdempotency;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.service.DataSyncAgentTaskExecutionService;
import com.czh.datasmart.govern.datasync.service.DataSyncService;
import com.czh.datasmart.govern.datasync.service.support.SyncCallbackIdempotencySupport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * Agent 数据同步执行服务实现。
 *
 * <p>本类的边界非常刻意：它只认识 `data-sync.execute` 这一种工具，不解析任意 URL，也不绕过 data-sync
 * 已有的模板校验、租户校验、项目范围校验和任务状态机。这样做的目标是让 Agent 能力进入真实业务系统时仍然走
 * “明确工具语义 + 明确权限边界 + 明确幂等键”的商业化产品路线，而不是 demo 式地把模型输出当成可执行 HTTP 请求。</p>
 */
@Service
@RequiredArgsConstructor
public class DataSyncAgentTaskExecutionServiceImpl implements DataSyncAgentTaskExecutionService {

    private static final String SUPPORTED_TOOL_CODE = "data-sync.execute";
    private static final String IDEMPOTENCY_ACTION = "AGENT_EXECUTE_SYNC_TASK";

    private final DataSyncService dataSyncService;
    private final SyncCallbackIdempotencySupport idempotencySupport;
    private final ObjectMapper objectMapper;

    /**
     * 幂等地创建并入队同步任务。
     *
     * <p>事务边界覆盖幂等登记、同步任务创建、执行记录创建、任务状态更新和幂等成功标记。
     * 如果中间任何一步失败，事务会整体回滚，幂等键不会被错误占用；worker 后续可以用同一个幂等键安全重试。</p>
     */
    @Override
    @Transactional
    public AgentSyncTaskExecuteResponse executeAgentSyncTask(AgentSyncTaskExecuteRequest request) {
        validateRequest(request);
        Long tenantId = request.getTenantId();
        String scopeKey = scopeKey(request);
        String idempotencyKey = request.getIdempotencyKey().trim();

        boolean duplicate = idempotencySupport.isDuplicate(
                tenantId,
                null,
                null,
                IDEMPOTENCY_ACTION,
                scopeKey,
                idempotencyKey,
                "task-management-agent-async-worker",
                requestDigest(request)
        );
        if (duplicate) {
            return duplicateResponse(request, tenantId, scopeKey, idempotencyKey);
        }

        SyncActorContext actorContext = actorContext(request);
        SyncTask task = dataSyncService.createTask(createTaskRequest(request), actorContext);
        SyncTaskOperationResult operationResult = dataSyncService.runTask(task.getId(), actorContext);
        SyncTask queuedTask = dataSyncService.getTask(task.getId(), actorContext);
        AgentSyncTaskExecuteResponse response = new AgentSyncTaskExecuteResponse(
                request.getCommandId().trim(),
                queuedTask.getId(),
                queuedTask.getLastExecutionId(),
                operationResult.state(),
                true,
                true,
                false,
                "Agent 工具 data-sync.execute 已幂等创建同步任务并提交入队"
        );
        idempotencySupport.markSucceeded(tenantId, IDEMPOTENCY_ACTION, scopeKey, idempotencyKey, toJson(response));
        return response;
    }

    private void validateRequest(AgentSyncTaskExecuteRequest request) {
        if (request == null) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, "Agent 同步执行请求不能为空");
        }
        if (!SUPPORTED_TOOL_CODE.equalsIgnoreCase(trimToEmpty(request.getToolCode()))) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "data-sync 内部 Agent 执行入口只支持工具 " + SUPPORTED_TOOL_CODE);
        }
        if (isBlank(request.getCommandId()) || isBlank(request.getIdempotencyKey())
                || isBlank(request.getSessionId()) || isBlank(request.getRunId()) || isBlank(request.getAuditId())) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "Agent 同步执行必须携带 commandId、idempotencyKey、sessionId、runId 和 auditId");
        }
        if (request.getTenantId() == null) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, "Agent 同步执行必须携带 tenantId");
        }
        if (templateId(request) == null) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "Agent 同步执行必须携带 templateId 或 syncTemplateId");
        }
    }

    private AgentSyncTaskExecuteResponse duplicateResponse(AgentSyncTaskExecuteRequest request,
                                                           Long tenantId,
                                                           String scopeKey,
                                                           String idempotencyKey) {
        SyncCallbackIdempotency record = idempotencySupport.findRecord(
                tenantId, IDEMPOTENCY_ACTION, scopeKey, idempotencyKey);
        if (record != null && record.getResponseSummary() != null && !record.getResponseSummary().isBlank()) {
            try {
                AgentSyncTaskExecuteResponse first = objectMapper.readValue(
                        record.getResponseSummary(), AgentSyncTaskExecuteResponse.class);
                return new AgentSyncTaskExecuteResponse(first.commandId(), first.syncTaskId(), first.syncExecutionId(),
                        first.state(), false, false, true, "命中 Agent 同步执行幂等记录，返回首次成功处理结果");
            } catch (JsonProcessingException ignored) {
                // 历史摘要如果不可解析，仍然不能重新创建任务；返回保守重复响应，交由调用方或运维台排查。
            }
        }
        return new AgentSyncTaskExecuteResponse(request.getCommandId(), null, null, "DUPLICATE_PROCESSING",
                false, false, true, "命中 Agent 同步执行幂等记录，但首次响应摘要暂不可用");
    }

    private CreateSyncTaskRequest createTaskRequest(AgentSyncTaskExecuteRequest request) {
        CreateSyncTaskRequest createRequest = new CreateSyncTaskRequest();
        createRequest.setTenantId(request.getTenantId());
        createRequest.setProjectId(request.getProjectId());
        createRequest.setWorkspaceId(request.getWorkspaceId());
        createRequest.setTemplateId(templateId(request));
        createRequest.setName(defaultName(request));
        createRequest.setDescription(defaultDescription(request));
        createRequest.setPriority(defaultText(request.getPriority(), "MEDIUM").toUpperCase(Locale.ROOT));
        createRequest.setRunMode(defaultText(request.getRunMode(), "TEMPLATE").toUpperCase(Locale.ROOT));
        createRequest.setOwnerId(request.getOwnerId());
        return createRequest;
    }

    private SyncActorContext actorContext(AgentSyncTaskExecuteRequest request) {
        return new SyncActorContext(
                request.getTenantId(),
                parseActorId(request.getActorId()),
                "SERVICE_ACCOUNT",
                request.getTraceId(),
                "PLATFORM",
                null,
                java.util.List.of(),
                false
        );
    }

    private Long templateId(AgentSyncTaskExecuteRequest request) {
        return request.getSyncTemplateId() == null ? request.getTemplateId() : request.getSyncTemplateId();
    }

    private String defaultName(AgentSyncTaskExecuteRequest request) {
        if (!isBlank(request.getName())) {
            return request.getName().trim();
        }
        return "Agent同步任务-" + request.getCommandId().trim();
    }

    private String defaultDescription(AgentSyncTaskExecuteRequest request) {
        if (!isBlank(request.getDescription())) {
            return request.getDescription().trim();
        }
        return "由 Agent 工具 data-sync.execute 触发，sessionId=" + request.getSessionId()
                + ", runId=" + request.getRunId()
                + ", auditId=" + request.getAuditId();
    }

    private String scopeKey(AgentSyncTaskExecuteRequest request) {
        return "agent:" + request.getSessionId().trim() + ":" + request.getRunId().trim() + ":" + request.getAuditId().trim();
    }

    private String requestDigest(AgentSyncTaskExecuteRequest request) {
        return "commandId=" + request.getCommandId()
                + ",toolCode=" + request.getToolCode()
                + ",templateId=" + templateId(request)
                + ",tenantId=" + request.getTenantId()
                + ",projectId=" + request.getProjectId()
                + ",workspaceId=" + request.getWorkspaceId();
    }

    private String toJson(AgentSyncTaskExecuteResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException exception) {
            return "syncTaskId=" + response.syncTaskId() + ",syncExecutionId=" + response.syncExecutionId();
        }
    }

    private Long parseActorId(String actorId) {
        if (isBlank(actorId)) {
            return null;
        }
        try {
            return Long.parseLong(actorId.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String defaultText(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
