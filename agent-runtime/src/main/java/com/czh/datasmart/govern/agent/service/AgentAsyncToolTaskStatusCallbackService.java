/**
 * @Author : Cui
 * @Date: 2026/05/31 23:58
 * @Description DataSmart Govern Backend - AgentAsyncToolTaskStatusCallbackService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service;

import com.czh.datasmart.govern.agent.controller.dto.AgentAsyncToolTaskStatusCallbackRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentAsyncToolTaskStatusCallbackResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionAuditView;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionState;
import com.czh.datasmart.govern.agent.service.audit.AgentToolExecutionAuditRecord;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Agent 异步工具任务状态回调服务。
 *
 * <p>该服务位于 agent-runtime 内部，负责把 task-management worker 的任务进度映射回 Agent 工具审计状态。
 * 这一步是类 Codex/Claude Code Agent 能力里的“durable action visibility”：模型发起的长耗时动作不能只在任务中心成功，
 * Agent 会话、前端事件流、Python 二轮推理和审计台也必须看到同一条工具审计事实。</p>
 *
 * <p>状态映射原则：</p>
 * <p>1. RUNNING 对应 EXECUTING，表示 worker 已经领取任务并开始执行白名单工具适配器；</p>
 * <p>2. SUCCEEDED 对应 SUCCEEDED，表示下游业务服务已返回成功摘要；</p>
 * <p>3. FAILED 对应 FAILED，表示不可恢复失败，需要展示给用户或进入人工排障；</p>
 * <p>4. DEFERRED 暂不新增 Agent 状态枚举，而是保持/推进到 EXECUTING，并更新 message 说明任务已回队列等待重试。</p>
 *
 * <p>为什么 DEFERRED 不立刻扩展 AgentToolExecutionState：
 * 当前状态枚举已经被事件投影、结果查询、Python feedback 和前端语义使用。贸然新增 RETRYING/DEFERRED 会带来多处兼容改造。
 * 首版先把“任务暂退避”解释为“异步执行仍在进行中”，等后台 worker、指标和前端重试视图稳定后，再升级状态机。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentAsyncToolTaskStatusCallbackService {

    private static final String EXECUTION_MODE_ASYNC_TASK = "ASYNC_TASK";

    private final AgentToolExecutionAuditService auditService;

    /**
     * 应用 task-management 发来的状态回调。
     *
     * @param sessionId Agent 会话 ID，必须与审计记录一致，防止跨会话篡改。
     * @param runId Agent Run ID，必须与审计记录一致，防止把历史 run 的任务状态写到当前 run。
     * @param auditId Agent 工具审计 ID。
     * @param request task-management worker 回调载荷。
     * @return 当前审计状态的轻量响应。
     */
    public AgentAsyncToolTaskStatusCallbackResponse applyStatusCallback(String sessionId,
                                                                        String runId,
                                                                        String auditId,
                                                                        AgentAsyncToolTaskStatusCallbackRequest request) {
        AgentToolExecutionAuditRecord record = auditService.requireExecutionAuditRecord(sessionId, runId, auditId);
        ensureAsyncTaskAudit(record);
        String status = normalizeStatus(request);
        AgentToolExecutionAuditView view = switch (status) {
            case "RUNNING" -> markRunning(record, request);
            case "SUCCEEDED" -> markSucceeded(record, request);
            case "FAILED" -> markFailed(record, request);
            case "DEFERRED" -> markDeferred(record, request);
            default -> throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "不支持的 Agent 异步工具任务状态，status=" + status);
        };
        return new AgentAsyncToolTaskStatusCallbackResponse(true, view.auditId(), view.state(), view.message());
    }

    private void ensureAsyncTaskAudit(AgentToolExecutionAuditRecord record) {
        if (!EXECUTION_MODE_ASYNC_TASK.equals(record.getExecutionMode())) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "当前工具审计不是 ASYNC_TASK，不能接收 task-management 异步任务回调，auditId=" + record.getAuditId());
        }
    }

    private AgentToolExecutionAuditView markRunning(AgentToolExecutionAuditRecord record,
                                                    AgentAsyncToolTaskStatusCallbackRequest request) {
        if (record.getState() == AgentToolExecutionState.EXECUTING) {
            return auditService.getExecutionAudit(record.getSessionId(), record.getRunId(), record.getAuditId());
        }
        if (record.getState() != AgentToolExecutionState.PLANNED) {
            throw stateConflict(record, "RUNNING");
        }
        AgentToolExecutionAuditRecord running = auditService.startExecutionWithMessage(
                record.getSessionId(),
                record.getRunId(),
                record.getAuditId(),
                safeMessage(request, "Agent 异步工具任务已被 worker 领取并开始执行。")
        );
        return auditService.getExecutionAudit(running.getSessionId(), running.getRunId(), running.getAuditId());
    }

    private AgentToolExecutionAuditView markSucceeded(AgentToolExecutionAuditRecord record,
                                                      AgentAsyncToolTaskStatusCallbackRequest request) {
        if (record.getState() == AgentToolExecutionState.SUCCEEDED) {
            return auditService.getExecutionAudit(record.getSessionId(), record.getRunId(), record.getAuditId());
        }
        AgentToolExecutionAuditRecord executable = ensureTerminalCallbackAllowed(record, "SUCCEEDED");
        return auditService.succeedExecution(executable, safeMessage(request, "Agent 异步工具任务执行成功。"),
                safeOutputSummary(request));
    }

    private AgentToolExecutionAuditView markFailed(AgentToolExecutionAuditRecord record,
                                                   AgentAsyncToolTaskStatusCallbackRequest request) {
        if (record.getState() == AgentToolExecutionState.FAILED) {
            return auditService.getExecutionAudit(record.getSessionId(), record.getRunId(), record.getAuditId());
        }
        AgentToolExecutionAuditRecord executable = ensureTerminalCallbackAllowed(record, "FAILED");
        return auditService.failExecution(executable, safeErrorCode(request), safeMessage(request, "Agent 异步工具任务执行失败。"));
    }

    private AgentToolExecutionAuditView markDeferred(AgentToolExecutionAuditRecord record,
                                                     AgentAsyncToolTaskStatusCallbackRequest request) {
        if (record.getState() == AgentToolExecutionState.PLANNED) {
            AgentToolExecutionAuditRecord running = auditService.startExecutionWithMessage(
                    record.getSessionId(),
                    record.getRunId(),
                    record.getAuditId(),
                    safeMessage(request, "Agent 异步工具任务临时退避，仍等待后续 worker 重试。")
            );
            return auditService.getExecutionAudit(running.getSessionId(), running.getRunId(), running.getAuditId());
        }
        if (record.getState() == AgentToolExecutionState.EXECUTING) {
            return auditService.updateExecutingMessage(record, safeMessage(request, "Agent 异步工具任务临时退避，仍等待后续 worker 重试。"));
        }
        throw stateConflict(record, "DEFERRED");
    }

    private AgentToolExecutionAuditRecord ensureTerminalCallbackAllowed(AgentToolExecutionAuditRecord record, String targetStatus) {
        if (record.getState() != AgentToolExecutionState.EXECUTING && record.getState() != AgentToolExecutionState.PLANNED) {
            throw stateConflict(record, targetStatus);
        }
        if (record.getState() == AgentToolExecutionState.PLANNED) {
            return auditService.startExecutionWithMessage(record.getSessionId(), record.getRunId(), record.getAuditId(),
                    "Agent 异步工具任务收到终态回调，自动补齐 EXECUTING 审计节点。");
        }
        return record;
    }

    private String normalizeStatus(AgentAsyncToolTaskStatusCallbackRequest request) {
        if (request == null || request.status() == null || request.status().isBlank()) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST, "Agent 异步工具任务状态不能为空");
        }
        return request.status().trim().toUpperCase();
    }

    private String safeMessage(AgentAsyncToolTaskStatusCallbackRequest request, String defaultMessage) {
        if (request == null || request.message() == null || request.message().isBlank()) {
            return defaultMessage;
        }
        return request.message().trim();
    }

    private String safeErrorCode(AgentAsyncToolTaskStatusCallbackRequest request) {
        if (request == null || request.errorCode() == null || request.errorCode().isBlank()) {
            return "AGENT_ASYNC_TOOL_TASK_FAILED";
        }
        return request.errorCode().trim();
    }

    private String safeOutputSummary(AgentAsyncToolTaskStatusCallbackRequest request) {
        if (request == null || request.outputSummary() == null || request.outputSummary().isBlank()) {
            return "Agent 异步工具任务已完成，详细结果请查看 task-management 任务结果和下游业务记录。";
        }
        return request.outputSummary().trim();
    }

    private PlatformBusinessException stateConflict(AgentToolExecutionAuditRecord record, String targetStatus) {
        return new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                "当前 Agent 工具审计状态不能接收 " + targetStatus + " 回调，auditId="
                        + record.getAuditId() + ", state=" + record.getState());
    }
}
