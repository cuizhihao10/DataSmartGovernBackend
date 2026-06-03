/**
 * @Author : Cui
 * @Date: 2026/06/03 23:27
 * @Description DataSmart Govern Backend - AgentToolSandboxPolicyQueryService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.execution;

import com.czh.datasmart.govern.agent.config.AgentRuntimeProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolSandboxPolicyView;
import com.czh.datasmart.govern.agent.service.AgentToolExecutionAuditService;
import com.czh.datasmart.govern.agent.service.audit.AgentToolExecutionAuditRecord;
import com.czh.datasmart.govern.agent.service.session.AgentRunRecord;
import com.czh.datasmart.govern.agent.service.session.AgentSessionMemoryStore;
import com.czh.datasmart.govern.agent.service.session.AgentSessionRecord;
import com.czh.datasmart.govern.agent.service.tool.sandbox.AgentToolSandboxPolicyService;
import com.czh.datasmart.govern.agent.service.tool.sandbox.AgentToolSandboxVerdict;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Agent 工具调用沙箱策略只读查询服务。
 *
 * <p>该服务专门服务“执行前诊断”，不负责审批、不负责执行、不推进状态。
 * 它的职责是先校验 session/run/audit 归属，再把沙箱 verdict 转换成 Controller 可返回的 DTO。</p>
 *
 * <p>为什么不让 Controller 直接调用 {@link AgentToolSandboxPolicyService}：
 * Controller 应该只描述路由、参数和响应，不应该知道如何查 session、如何校验 run 归属、如何读取审计记录。
 * 把这些读取逻辑放在 QueryService 里，可以避免 Controller 越来越胖，也符合用户要求的解耦和单文件规模控制。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentToolSandboxPolicyQueryService {

    private final AgentRuntimeProperties properties;
    private final AgentSessionMemoryStore sessionMemoryStore;
    private final AgentToolExecutionAuditService auditService;
    private final AgentToolSandboxPolicyService sandboxPolicyService;

    /**
     * 查询单个工具计划的沙箱预检结果。
     *
     * @param sessionId Agent 会话 ID。
     * @param runId Agent Run ID。
     * @param auditId 工具执行审计 ID。
     * @return 单个工具计划的沙箱策略视图。
     */
    public AgentToolSandboxPolicyView inspectToolSandboxPolicy(String sessionId, String runId, String auditId) {
        ensureRuntimeEnabled();
        AgentSessionRecord session = requireSession(sessionId);
        AgentRunRecord run = requireRun(session, runId);
        AgentToolExecutionAuditRecord audit = auditService.requireExecutionAuditRecord(sessionId, runId, auditId);
        AgentToolSandboxVerdict verdict = sandboxPolicyService.inspect(session, run, audit);
        return toView(sessionId, runId, verdict);
    }

    private AgentToolSandboxPolicyView toView(String sessionId, String runId, AgentToolSandboxVerdict verdict) {
        return new AgentToolSandboxPolicyView(
                sessionId,
                runId,
                verdict.auditId(),
                verdict.toolCode(),
                verdict.sandboxEnabled(),
                verdict.allowed(),
                verdict.isolationMode(),
                verdict.riskLevel(),
                verdict.executionMode(),
                verdict.targetService(),
                verdict.argumentBytes(),
                verdict.maxArgumentBytes(),
                verdict.timeoutMs(),
                verdict.maxSyncTimeoutMs(),
                verdict.maxRetries(),
                verdict.issueCodes(),
                verdict.reasons(),
                verdict.recommendedActions()
        );
    }

    private void ensureRuntimeEnabled() {
        if (!Boolean.TRUE.equals(properties.getEnabled())) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT, "Agent Runtime 当前未启用");
        }
    }

    private AgentSessionRecord requireSession(String sessionId) {
        return sessionMemoryStore.findById(sessionId)
                .orElseThrow(() -> new PlatformBusinessException(
                        PlatformErrorCode.NOT_FOUND,
                        "Agent 会话不存在，sessionId=" + sessionId
                ));
    }

    private AgentRunRecord requireRun(AgentSessionRecord session, String runId) {
        return session.getRuns().stream()
                .filter(run -> run.getRunId().equals(runId))
                .findFirst()
                .orElseThrow(() -> new PlatformBusinessException(
                        PlatformErrorCode.NOT_FOUND,
                        "Agent Run 不属于当前会话或不存在，runId=" + runId
                ));
    }
}
