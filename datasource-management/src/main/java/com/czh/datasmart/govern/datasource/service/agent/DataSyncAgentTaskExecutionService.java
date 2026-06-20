/**
 * @Author : Cui
 * @Date: 2026/06/20 23:20
 * @Description DataSmart Govern Backend - DataSyncAgentTaskExecutionService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.agent;

import com.czh.datasmart.govern.datasource.controller.dto.CreateSyncTaskRequest;
import com.czh.datasmart.govern.datasource.controller.dto.DataSyncAgentExecuteRequest;
import com.czh.datasmart.govern.datasource.controller.dto.DataSyncAgentExecuteResponse;
import com.czh.datasmart.govern.datasource.controller.dto.SyncActionRequest;
import com.czh.datasmart.govern.datasource.entity.DataSyncAgentCommandReceipt;
import com.czh.datasmart.govern.datasource.entity.SyncTask;
import com.czh.datasmart.govern.datasource.service.SyncTaskService;
import com.czh.datasmart.govern.datasource.support.PriorityLevel;
import com.czh.datasmart.govern.datasource.support.RunMode;
import com.czh.datasmart.govern.datasource.support.SyncTaskState;
import com.czh.datasmart.govern.datasource.support.TriggerType;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Agent data-sync.execute 命令接收与同步任务落地服务。
 *
 * <p>它是 task-management worker 与 datasource-management 同步任务控制面之间的防腐层。
 * 防腐层的意义是：上游只表达“已治理、已确认、可以触发 data-sync.execute”，下游负责把这个意图转换成
 * datasource 模块自己的任务、队列和审计模型，而不是让上游直接操作 sync_task 表。</p>
 *
 * <p>核心流程：</p>
 * <p>1. 校验命令字段和工具白名单。</p>
 * <p>2. 先查 receipt，命中则返回幂等结果。</p>
 * <p>3. 插入 RECEIVED receipt 抢占 commandId/idempotencyKey。</p>
 * <p>4. 复用 SyncTaskService 创建同步任务，继续走模板、权限、审计、默认状态等现有规则。</p>
 * <p>5. 复用 SyncTaskService.enqueue 把任务推进到 QUEUED。</p>
 * <p>6. 更新 receipt，返回 syncTaskId 和低敏状态。</p>
 *
 * <p>为什么当前只入队而不直接 run？</p>
 * <p>datasource-management 已经具备单批 Runner，但真实生产链路仍需要 worker claim、租约、分片、暂停/取消信号和容量控制。
 * 所以这个入口只负责把 Agent 命令变成“可被同步 worker 认领的任务”，不绕过队列直接开始搬运数据。</p>
 */
@Service
@RequiredArgsConstructor
public class DataSyncAgentTaskExecutionService {

    /**
     * 当前入口唯一支持的工具编码。
     */
    public static final String TOOL_CODE = "data-sync.execute";

    /**
     * datasource 侧 receipt ID 前缀。
     */
    private static final String RECEIPT_PREFIX = "datasource-agent-receipt:";

    /**
     * 当前任务创建和入队使用的机器角色。
     *
     * <p>原始 actorId 仍会保留到任务 createdBy/ownerId 的可解析部分；SERVICE_ACCOUNT 表达的是：
     * 这次副作用由 task-management worker 代表已确认的 Agent 命令执行。</p>
     */
    private static final String SERVICE_ACCOUNT_ROLE = "SERVICE_ACCOUNT";

    /**
     * 无法把 actorId 解析为 Long 时使用的本地系统账号。
     */
    private static final Long SYSTEM_ACTOR_ID = 0L;

    private final DataSyncAgentCommandReceiptStore receiptStore;
    private final SyncTaskService syncTaskService;

    /**
     * 接收 Agent 命令并创建/入队同步任务。
     *
     * @param request task-management worker 发来的低敏命令。
     * @return 可回写给 task-management outbox 的低敏 receipt 响应。
     */
    @Transactional
    public DataSyncAgentExecuteResponse execute(DataSyncAgentExecuteRequest request) {
        validateRequest(request);
        return receiptStore.findByCommandOrIdempotencyKey(request.getCommandId(), request.getIdempotencyKey())
                .map(existing -> duplicateResponse(existing, request))
                .orElseGet(() -> createTaskAfterReceiptClaim(request));
    }

    /**
     * 抢占幂等键后创建并入队同步任务。
     *
     * <p>注意顺序非常重要：先插 receipt，再创建任务。
     * 如果两个 dispatcher 并发投递同一 command，只有一个事务能成功插入唯一键；
     * 失败的一方会捕获 DuplicateKeyException 并回查已有 receipt，从而避免创建第二个 sync_task。</p>
     */
    private DataSyncAgentExecuteResponse createTaskAfterReceiptClaim(DataSyncAgentExecuteRequest request) {
        DataSyncAgentCommandReceipt receipt = buildInitialReceipt(request);
        try {
            receiptStore.insert(receipt);
        } catch (DuplicateKeyException exception) {
            return receiptStore.findByCommandOrIdempotencyKey(request.getCommandId(), request.getIdempotencyKey())
                    .map(existing -> duplicateResponse(existing, request))
                    .orElseThrow(() -> exception);
        }

        SyncTask created = syncTaskService.createTask(buildCreateTaskRequest(request));
        SyncTask queued = syncTaskService.enqueue(created.getId(), buildEnqueueRequest(request));
        markQueued(receipt, queued);
        receiptStore.updateById(receipt);
        return response(receipt, true, false);
    }

    /**
     * 处理幂等重复命中。
     *
     * <p>如果 commandId 与 idempotencyKey 的绑定关系不一致，说明可能出现了错误重放或调用方复用了别人的幂等键。
     * 这种情况不能返回旧结果，否则会把一个命令错误绑定到另一个命令的同步任务上。</p>
     */
    private DataSyncAgentExecuteResponse duplicateResponse(DataSyncAgentCommandReceipt existing,
                                                           DataSyncAgentExecuteRequest request) {
        if (!request.getCommandId().equals(existing.getCommandId())
                || !request.getIdempotencyKey().equals(existing.getIdempotencyKey())) {
            throw new IllegalStateException("DataSync Agent 命令幂等绑定冲突，commandId 与 idempotencyKey 不匹配");
        }
        return response(existing, false, true);
    }

    private DataSyncAgentCommandReceipt buildInitialReceipt(DataSyncAgentExecuteRequest request) {
        Long resolvedTemplateId = resolveTemplateId(request);
        DataSyncAgentCommandReceipt receipt = new DataSyncAgentCommandReceipt();
        receipt.setReceiptId(RECEIPT_PREFIX + request.getCommandId());
        receipt.setCommandId(request.getCommandId());
        receipt.setIdempotencyKey(request.getIdempotencyKey());
        receipt.setAgentSessionId(trimToNull(request.getSessionId()));
        receipt.setAgentRunId(trimToNull(request.getRunId()));
        receipt.setAuditId(trimToNull(request.getAuditId()));
        receipt.setToolCode(TOOL_CODE);
        receipt.setTenantId(request.getTenantId());
        receipt.setProjectId(request.getProjectId());
        receipt.setWorkspaceId(request.getWorkspaceId());
        receipt.setActorId(trimToNull(request.getActorId()));
        receipt.setTraceId(trimToNull(request.getTraceId()));
        receipt.setTemplateId(request.getTemplateId());
        receipt.setSyncTemplateId(request.getSyncTemplateId());
        receipt.setResolvedTemplateId(resolvedTemplateId);
        receipt.setStatus("RECEIVED");
        receipt.setDownstreamState("RECEIVED");
        receipt.setSideEffectStarted(false);
        receipt.setSideEffectExecuted(false);
        receipt.setDuplicate(false);
        receipt.setMessage("命令已被 datasource-management 接收，正在转换为同步任务");
        return receipt;
    }

    private CreateSyncTaskRequest buildCreateTaskRequest(DataSyncAgentExecuteRequest request) {
        Long actorId = resolveActorId(request);
        CreateSyncTaskRequest taskRequest = new CreateSyncTaskRequest();
        taskRequest.setTenantId(request.getTenantId());
        taskRequest.setTemplateId(resolveTemplateId(request));
        taskRequest.setName(buildTaskName(request));
        taskRequest.setDescription("由 Agent data-sync.execute 受控命令创建；原始工具参数不复制到同步任务描述。");
        taskRequest.setApprovalRequired(false);
        taskRequest.setPriority(defaultEnumValue(request.getPriority(), PriorityLevel.MEDIUM.name()));
        taskRequest.setRunMode(defaultEnumValue(request.getRunMode(), RunMode.MANUAL.name()));
        taskRequest.setTriggerType(TriggerType.EVENT.name());
        taskRequest.setOwnerId(resolveOwnerId(request, actorId));
        taskRequest.setEnabled(true);
        taskRequest.setOperatorAttentionRequired(false);
        taskRequest.setTimeoutSeconds(1800);
        taskRequest.setMaxRetryCount(3);
        taskRequest.setIncidentNote("Agent 命令创建，commandId=" + request.getCommandId());
        taskRequest.setCreatedBy(actorId);
        taskRequest.setActorRole(SERVICE_ACCOUNT_ROLE);
        taskRequest.setActorTenantId(request.getTenantId());
        return taskRequest;
    }

    private SyncActionRequest buildEnqueueRequest(DataSyncAgentExecuteRequest request) {
        SyncActionRequest actionRequest = new SyncActionRequest();
        actionRequest.setActorId(resolveActorId(request));
        actionRequest.setActorRole(SERVICE_ACCOUNT_ROLE);
        actionRequest.setActorTenantId(request.getTenantId());
        actionRequest.setNote("Agent data-sync.execute 命令已通过上游治理，进入同步任务队列，commandId=" + request.getCommandId());
        return actionRequest;
    }

    private void markQueued(DataSyncAgentCommandReceipt receipt, SyncTask queued) {
        receipt.setSyncTaskId(queued.getId());
        receipt.setSyncExecutionId(queued.getLastExecutionId());
        receipt.setStatus("QUEUED");
        receipt.setDownstreamState(queued.getCurrentState());
        receipt.setSideEffectStarted(true);
        receipt.setSideEffectExecuted(true);
        receipt.setDuplicate(false);
        receipt.setMessage("同步任务已创建并进入待执行队列");
    }

    private DataSyncAgentExecuteResponse response(DataSyncAgentCommandReceipt receipt,
                                                  boolean created,
                                                  boolean duplicate) {
        return new DataSyncAgentExecuteResponse(
                receipt.getCommandId(),
                receipt.getSyncTaskId(),
                receipt.getSyncExecutionId(),
                receipt.getDownstreamState(),
                created,
                SyncTaskState.QUEUED.name().equalsIgnoreCase(receipt.getDownstreamState()),
                duplicate,
                receipt.getMessage()
        );
    }

    private void validateRequest(DataSyncAgentExecuteRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("DataSync Agent 执行请求不能为空");
        }
        requireText(request.getCommandId(), "commandId");
        requireText(request.getIdempotencyKey(), "idempotencyKey");
        requireText(request.getToolCode(), "toolCode");
        if (!TOOL_CODE.equalsIgnoreCase(request.getToolCode().trim())) {
            throw new IllegalArgumentException("当前内部入口只支持工具 " + TOOL_CODE);
        }
        if (request.getTenantId() == null || request.getTenantId() <= 0) {
            throw new IllegalArgumentException("tenantId 必须大于 0");
        }
        if (resolveTemplateId(request) == null || resolveTemplateId(request) <= 0) {
            throw new IllegalArgumentException("templateId 或 syncTemplateId 必须提供且大于 0");
        }
    }

    private Long resolveTemplateId(DataSyncAgentExecuteRequest request) {
        return request.getSyncTemplateId() != null ? request.getSyncTemplateId() : request.getTemplateId();
    }

    private Long resolveOwnerId(DataSyncAgentExecuteRequest request, Long actorId) {
        if (request.getOwnerId() != null && request.getOwnerId() > 0) {
            return request.getOwnerId();
        }
        return actorId == null ? SYSTEM_ACTOR_ID : actorId;
    }

    private Long resolveActorId(DataSyncAgentExecuteRequest request) {
        Long parsed = parseLong(request.getActorId());
        if (parsed != null && parsed > 0) {
            return parsed;
        }
        if (request.getOwnerId() != null && request.getOwnerId() > 0) {
            return request.getOwnerId();
        }
        return SYSTEM_ACTOR_ID;
    }

    private String buildTaskName(DataSyncAgentExecuteRequest request) {
        String baseName = trimToNull(request.getName());
        if (baseName == null) {
            baseName = "Agent DataSync";
        }
        String suffix = " - agent " + shortCommandId(request.getCommandId());
        int maxBaseLength = 128 - suffix.length();
        if (baseName.length() > maxBaseLength) {
            baseName = baseName.substring(0, maxBaseLength);
        }
        return baseName + suffix;
    }

    private String shortCommandId(String commandId) {
        String normalized = commandId == null ? "" : commandId.replaceAll("[^A-Za-z0-9]", "");
        if (normalized.length() <= 10) {
            return normalized;
        }
        return normalized.substring(0, 10);
    }

    private Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String defaultEnumValue(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim().toUpperCase();
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
    }
}
