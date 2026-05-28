/**
 * @Author : Cui
 * @Date: 2026/05/24 13:39
 * @Description DataSmart Govern Backend - AgentToolExecutionGuard.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.tool;

import com.czh.datasmart.govern.agent.service.audit.AgentToolExecutionAuditRecord;
import com.czh.datasmart.govern.agent.service.session.AgentRunRecord;
import com.czh.datasmart.govern.agent.service.session.AgentSessionRecord;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;

/**
 * Agent 工具执行前置守卫。
 *
 * <p>AgentPlan 接入阶段只说明“模型计划了什么工具”，真正执行工具时还必须重新检查控制面边界。
 * 这类检查不能放到某一个具体工具适配器里，否则每个适配器都可能漏掉一部分安全规则。
 *
 * <p>当前守卫覆盖四类商业化基础风险：
 * 1. 会话、Run、审计记录不属于同一条控制面链路，拒绝执行；
 * 2. 租户、项目、工作空间、actor 与会话边界不一致，拒绝执行；
 * 3. Python 参数校验仍存在 missingFields，拒绝执行；
 * 4. 非只读工具如果没有人工审批记录，拒绝执行。
 *
 * <p>后续可在这里继续叠加：租户配额、项目敏感级别、工具调用时间窗口、服务账号白名单、下游健康熔断、
 * 分布式执行幂等键、参数 schema 强类型校验等，而不需要重写每个工具适配器。
 */
@Component
public class AgentToolExecutionGuard {

    /**
     * 执行前校验控制面边界和工具风险。
     *
     * @param session 当前会话事实。
     * @param run 当前运行事实。
     * @param audit 即将执行的工具审计事实。
     */
    public void validateBeforeExecution(AgentSessionRecord session,
                                        AgentRunRecord run,
                                        AgentToolExecutionAuditRecord audit) {
        ensureSameControlPlane(session, run, audit);
        ensureSameBusinessBoundary(session, audit);
        ensureParameterValidationPassed(audit);
        ensureWriteToolApproved(audit);
    }

    private void ensureSameControlPlane(AgentSessionRecord session,
                                        AgentRunRecord run,
                                        AgentToolExecutionAuditRecord audit) {
        if (!Objects.equals(session.getSessionId(), run.getSessionId())
                || !Objects.equals(session.getSessionId(), audit.getSessionId())
                || !Objects.equals(run.getRunId(), audit.getRunId())) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "工具审计记录与当前 Agent 会话/Run 不一致，拒绝执行。");
        }
    }

    private void ensureSameBusinessBoundary(AgentSessionRecord session,
                                            AgentToolExecutionAuditRecord audit) {
        if (!Objects.equals(session.getTenantId(), audit.getTenantId())
                || !Objects.equals(session.getProjectId(), audit.getProjectId())
                || !Objects.equals(session.getWorkspaceId(), audit.getWorkspaceId())
                || !Objects.equals(session.getActorId(), audit.getActorId())) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "工具审计记录的租户/项目/工作空间/actor 与当前会话不一致，拒绝跨边界执行。");
        }
    }

    private void ensureParameterValidationPassed(AgentToolExecutionAuditRecord audit) {
        Map<String, Object> validation = audit.getParameterValidation();
        if (validation == null || validation.isEmpty()) {
            return;
        }
        Object missingFields = validation.get("missingFields");
        if (missingFields instanceof Collection<?> collection && !collection.isEmpty()) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "工具参数仍存在缺失字段，必须先补齐参数或重新规划，missingFields=" + collection);
        }
    }

    private void ensureWriteToolApproved(AgentToolExecutionAuditRecord audit) {
        boolean readOnly = Boolean.TRUE.equals(audit.getReadOnly());
        if (readOnly) {
            return;
        }
        if (audit.getApprovalOperatorId() == null || audit.getApprovalOperatorId().isBlank()) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "非只读工具必须先完成人工审批，不能直接执行，toolCode=" + audit.getToolCode());
        }
    }
}
