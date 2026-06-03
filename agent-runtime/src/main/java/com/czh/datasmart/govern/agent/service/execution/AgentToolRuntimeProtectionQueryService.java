/**
 * @Author : Cui
 * @Date: 2026/06/04 00:09
 * @Description DataSmart Govern Backend - AgentToolRuntimeProtectionQueryService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.execution;

import com.czh.datasmart.govern.agent.config.AgentRuntimeProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolRuntimeProtectionPolicyView;
import com.czh.datasmart.govern.agent.service.AgentToolExecutionAuditService;
import com.czh.datasmart.govern.agent.service.audit.AgentToolExecutionAuditRecord;
import com.czh.datasmart.govern.agent.service.session.AgentRunRecord;
import com.czh.datasmart.govern.agent.service.session.AgentSessionMemoryStore;
import com.czh.datasmart.govern.agent.service.session.AgentSessionRecord;
import com.czh.datasmart.govern.agent.service.tool.protection.AgentToolRuntimeProtectionService;
import com.czh.datasmart.govern.agent.service.tool.protection.AgentToolRuntimeProtectionVerdict;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Agent 工具运行时保护只读查询服务。
 *
 * <p>该服务和 `AgentToolSandboxPolicyQueryService` 形成互补：
 * - sandbox-policy 回答“工具计划是否安全、合规、符合目录与审批约束”；
 * - runtime-protection-policy 回答“即使工具安全，现在是否因为并发、租户压力或下游熔断而应该暂缓执行”。</p>
 *
 * <p>Controller 不直接读取 session/run/audit，是为了保持路由层轻薄。
 * 查询服务负责做归属校验、读取审计记录、调用领域服务并转换 DTO，
 * 后续如果接入权限中心或审计查询权限，也可以集中加在这里。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentToolRuntimeProtectionQueryService {

    private final AgentRuntimeProperties properties;
    private final AgentSessionMemoryStore sessionMemoryStore;
    private final AgentToolExecutionAuditService auditService;
    private final AgentToolRuntimeProtectionService runtimeProtectionService;

    /**
     * 查询单个工具计划的运行时保护 verdict。
     *
     * @param sessionId Agent 会话 ID，用于限定工作空间边界。
     * @param runId Agent Run ID，用于限定本次编排尝试。
     * @param auditId 工具执行审计 ID，用于定位具体工具计划。
     * @return 运行时容量与熔断策略视图。
     */
    public AgentToolRuntimeProtectionPolicyView inspectToolRuntimeProtection(String sessionId, String runId, String auditId) {
        ensureRuntimeEnabled();
        AgentSessionRecord session = requireSession(sessionId);
        AgentRunRecord run = requireRun(session, runId);
        AgentToolExecutionAuditRecord audit = auditService.requireExecutionAuditRecord(sessionId, runId, auditId);
        AgentToolRuntimeProtectionVerdict verdict = runtimeProtectionService.inspect(session, run, audit);
        return toView(sessionId, runId, verdict);
    }

    private AgentToolRuntimeProtectionPolicyView toView(String sessionId,
                                                        String runId,
                                                        AgentToolRuntimeProtectionVerdict verdict) {
        return new AgentToolRuntimeProtectionPolicyView(
                sessionId,
                runId,
                verdict.auditId(),
                verdict.toolCode(),
                verdict.targetService(),
                verdict.tenantId(),
                verdict.protectionEnabled(),
                verdict.allowed(),
                verdict.globalInFlight(),
                verdict.tenantInFlight(),
                verdict.targetServiceInFlight(),
                verdict.maxGlobalInFlight(),
                verdict.maxTenantInFlight(),
                verdict.maxTargetServiceInFlight(),
                verdict.circuitBreakerEnabled(),
                verdict.circuitOpen(),
                verdict.circuitOpenUntil(),
                verdict.consecutiveFailures(),
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
