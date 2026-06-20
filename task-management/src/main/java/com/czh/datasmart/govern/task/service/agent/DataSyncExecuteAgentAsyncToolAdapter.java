/**
 * @Author : Cui
 * @Date: 2026/05/31 23:35
 * @Description DataSmart Govern Backend - DataSyncExecuteAgentAsyncToolAdapter.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

import com.czh.datasmart.govern.task.service.datasync.DataSyncWorkerCommandDeliveryResult;
import com.czh.datasmart.govern.task.service.datasync.DataSyncWorkerCommandDeliveryService;
import com.czh.datasmart.govern.task.service.datasync.DataSyncWorkerCommandOutboxService;
import com.czh.datasmart.govern.task.service.datasync.DataSyncWorkerCommandStageRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@code data-sync.execute} Agent 异步工具适配器。
 *
 * <p>该适配器是 Agent worker 到 DataSync 主链路的入口适配层。它的职责不是直接执行 JDBC 同步，也不是直接理解
 * datasource-management 的内部任务状态机，而是把 Agent Runtime 交付的结构化工具 payload 转换成 task-management
 * 本地 outbox 命令。</p>
 *
 * <p>当前职责边界：</p>
 * <p>1. 校验并解析 Agent payload 中的 templateId/syncTemplateId 等低敏控制字段；</p>
 * <p>2. 写入 task-management 本地 DataSync worker command outbox，形成可追踪、可重放、可补偿的本地事实；</p>
 * <p>3. 调用 {@link DataSyncWorkerCommandDeliveryService} 完成真实下游投递；</p>
 * <p>4. 把 delivery 结果转换成 Agent worker 统一的成功、可重试失败或永久失败结果。</p>
 *
 * <p>为什么不再在这里直接写 RestClient：</p>
 * <p>早期版本中，适配器同时负责 outbox 入箱、HTTP 调用、receipt 构造和失败回写，导致职责偏重。
 * 现在把真实投递提取到 delivery 服务后，手动 dispatch-batch、后台 scheduler、未来 Kafka publisher 都能复用同一套
 * 投递与状态流转语义，避免后续继续在一个适配器里堆积跨服务逻辑。</p>
 */
@Component
@RequiredArgsConstructor
public class DataSyncExecuteAgentAsyncToolAdapter implements AgentAsyncToolExecutor {

    /**
     * Agent 工具编码。
     *
     * <p>该编码必须与 Python AI Runtime、Agent Runtime、permission-admin 审批事实、task-management outbox
     * 和 datasource-management 内部入口保持一致。工具编码是跨模块契约，不应该随意改名。</p>
     */
    public static final String TOOL_CODE = "data-sync.execute";

    /**
     * outbox 入箱服务，只负责创建或复用本地命令账本。
     */
    private final DataSyncWorkerCommandOutboxService outboxService;

    /**
     * outbox 投递服务，负责 DISPATCHING、下游调用、receipt 和失败状态回写。
     */
    private final DataSyncWorkerCommandDeliveryService deliveryService;

    @Override
    public boolean supports(String toolCode) {
        return TOOL_CODE.equalsIgnoreCase(toolCode == null ? "" : toolCode.trim());
    }

    @Override
    public AgentAsyncToolExecutionResult execute(AgentAsyncToolResolvedPayload payload) {
        DataSyncAgentExecuteRequest request = buildRequest(payload);

        /*
         * 先写本地 outbox，再越过 data-sync 副作用边界。
         *
         * 这个顺序非常重要：如果先调用下游，再写本地命令账本，一旦 task-management 在下游成功后宕机，
         * 本地就无法知道自己曾经发起过 data-sync.execute，后续补偿、审计和幂等判断都会丢失证据。
         * 因此生产级跨服务副作用通常要先记录本地 durable command，再由 dispatcher 投递给下游。
         */
        outboxService.stageCommand(stageRequest(payload, request));
        DataSyncWorkerCommandDeliveryResult delivery = deliveryService.deliverCommand(request.getCommandId());

        if (delivery.success()) {
            return AgentAsyncToolExecutionResult.success(delivery.message(), delivery.toAgentOutput());
        }
        if (delivery.retryable()) {
            return AgentAsyncToolExecutionResult.retryableFailure(delivery.message(), delivery.toAgentOutput());
        }
        return AgentAsyncToolExecutionResult.fatalFailure(delivery.message(), delivery.toAgentOutput());
    }

    /**
     * 从 Agent worker 已解析 payload 中构造 datasource-management 内部执行请求。
     *
     * <p>该请求本身不会直接持久化到 outbox。outbox 只保存 commandId、idempotencyKey、tenant/project、templateId、
     * syncTemplateId、priority、runMode、ownerId 等低敏字段；name/description 这类用户文本不会写入 outbox，
     * 避免自然语言上下文、筛选条件或业务路径进入跨服务命令账本。</p>
     */
    private DataSyncAgentExecuteRequest buildRequest(AgentAsyncToolResolvedPayload payload) {
        Long templateId = longArgument(payload, "templateId");
        Long syncTemplateId = longArgument(payload, "syncTemplateId");
        if (templateId == null && syncTemplateId == null) {
            throw new IllegalStateException("data-sync.execute 必须在 planArguments 中提供 templateId 或 syncTemplateId");
        }
        DataSyncAgentExecuteRequest request = new DataSyncAgentExecuteRequest();
        request.setCommandId(payload.commandId());
        request.setIdempotencyKey("agent-async-tool:" + payload.commandId());
        request.setAuditId(payload.auditId());
        request.setSessionId(payload.sessionId());
        request.setRunId(payload.runId());
        request.setToolCode(payload.toolCode());
        request.setTenantId(payload.tenantId());
        request.setProjectId(payload.projectId());
        request.setWorkspaceId(payload.workspaceId());
        request.setActorId(payload.actorId());
        request.setTraceId(payload.traceId());
        request.setTemplateId(templateId);
        request.setSyncTemplateId(syncTemplateId);
        request.setName(stringArgument(payload, "name"));
        request.setDescription(stringArgument(payload, "description"));
        request.setPriority(stringArgument(payload, "priority"));
        request.setRunMode(stringArgument(payload, "runMode"));
        request.setOwnerId(longArgument(payload, "ownerId"));
        return request;
    }

    /**
     * 构造 DataSync worker command outbox 入箱请求。
     *
     * <p>这里传入的是低敏控制字段，而不是完整工具实参。即使 Agent payload 中包含 name/description，
     * outbox 也不会保存这些文本。后续 dispatcher 重试时，应依赖 commandId、idempotencyKey、templateId、
     * priority、runMode、ownerId 等稳定低敏字段完成下游调用。</p>
     */
    private DataSyncWorkerCommandStageRequest stageRequest(AgentAsyncToolResolvedPayload payload,
                                                           DataSyncAgentExecuteRequest request) {
        return new DataSyncWorkerCommandStageRequest(
                request.getCommandId(),
                request.getIdempotencyKey(),
                payload.taskId(),
                payload.sessionId(),
                payload.runId(),
                payload.auditId(),
                payload.toolCode(),
                payload.targetService(),
                DataSyncWorkerCommandOutboxService.DEFAULT_OPERATION,
                payload.tenantId(),
                payload.projectId(),
                payload.workspaceId(),
                payload.actorId(),
                payload.traceId(),
                request.getTemplateId(),
                request.getSyncTemplateId(),
                request.getPriority(),
                request.getRunMode(),
                request.getOwnerId()
        );
    }

    private Long longArgument(AgentAsyncToolResolvedPayload payload, String name) {
        Object value = payload.planArguments().get(name);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Long.parseLong(text.trim());
        }
        return null;
    }

    private String stringArgument(AgentAsyncToolResolvedPayload payload, String name) {
        Object value = payload.planArguments().get(name);
        return value == null ? null : String.valueOf(value).trim();
    }
}
