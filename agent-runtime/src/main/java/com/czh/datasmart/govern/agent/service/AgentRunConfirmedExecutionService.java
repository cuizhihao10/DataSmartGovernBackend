/**
 * @Author : Cui
 * @Date: 2026/07/10 00:00
 * @Description DataSmart Govern Backend - AgentRunConfirmedExecutionService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service;

import com.czh.datasmart.govern.agent.controller.dto.AgentRunConfirmedExecutionRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunConfirmedExecutionResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionAuditView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionDecisionRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionResultView;
import com.czh.datasmart.govern.agent.service.session.AgentRunRecord;
import com.czh.datasmart.govern.agent.service.session.AgentSessionMemoryStore;
import com.czh.datasmart.govern.agent.service.session.AgentSessionRecord;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 用户确认后的 Agent DAG 串行执行服务。
 *
 * <p>当前垂直闭环按计划顺序执行节点。Python ToolPlan 已把连接测试、元数据、草稿、预检查、发布、运行、状态查询
 * 编成可观测 DAG；Java 在执行时再次使用审计状态、工具 Guard、项目边界和下游业务状态机校验。后续可以把无依赖
 * 只读节点并行化，但写节点仍应保持依赖顺序和失败即阻断语义。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentRunConfirmedExecutionService {

    private final AgentSessionMemoryStore sessionStore;
    private final AgentSessionService sessionService;
    private final AgentToolExecutionAuditService auditService;

    public AgentRunConfirmedExecutionResponse confirmAndExecute(
            String sessionId,
            String runId,
            AgentRunConfirmedExecutionRequest request,
            Long tenantId,
            Long projectId,
            String actorId,
            String actorRole,
            String actorType,
            String authorizedProjectRoles,
            String traceId) {
        if (request == null || !Boolean.TRUE.equals(request.confirmed())) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "必须显式确认后才能执行 Agent 计划");
        }
        AgentSessionRecord session = requireInitiatorSession(sessionId, tenantId, projectId, actorId);
        synchronized (session) {
            requireDelegatedIdentity(actorRole, authorizedProjectRoles, projectId);
            session.refreshDelegatedIdentity(actorRole, actorType, authorizedProjectRoles);
            AgentRunRecord run = requireRun(session, runId);
            List<AgentToolExecutionAuditView> audits = auditService.listByRun(sessionId, runId);
            if (audits.isEmpty()) {
                throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                        "当前 Agent Run 没有可执行工具计划");
            }

            AgentToolExecutionDecisionRequest decision = new AgentToolExecutionDecisionRequest(
                    actorId,
                    normalizeComment(request.comment())
            );
            for (AgentToolExecutionAuditView audit : audits) {
                if ("WAITING_APPROVAL".equals(audit.state())) {
                    sessionService.approveToolExecution(sessionId, runId, audit.auditId(), decision);
                }
            }

            List<AgentToolExecutionResultView> results = new ArrayList<>();
            int succeeded = 0;
            int failed = 0;
            List<AgentToolExecutionAuditView> executableAudits = auditService.listByRun(sessionId, runId);
            for (AgentToolExecutionAuditView audit : executableAudits) {
                if ("SUCCEEDED".equals(audit.state())) {
                    succeeded++;
                    continue;
                }
                if (!"PLANNED".equals(audit.state())) {
                    failed++;
                    run.failAfterToolExecution("工具节点状态不允许继续执行，toolCode=" + audit.toolCode()
                            + "，state=" + audit.state());
                    break;
                }
                AgentToolExecutionResultView result = sessionService.executeToolExecution(
                        sessionId, runId, audit.auditId(), traceId);
                results.add(result);
                if ("SUCCEEDED".equals(result.audit().state())) {
                    succeeded++;
                    continue;
                }
                failed++;
                run.failAfterToolExecution("Agent 工具节点执行失败，toolCode=" + audit.toolCode()
                        + "，请根据节点错误修复后重新发起计划。");
                break;
            }
            if (failed == 0 && succeeded == audits.size()) {
                run.completeAfterToolExecution("Agent 计划全部工具节点执行成功；同步任务已提交业务执行链路。");
            }
            return new AgentRunConfirmedExecutionResponse(
                    sessionId,
                    runId,
                    run.getState().name(),
                    audits.size(),
                    succeeded,
                    failed,
                    List.copyOf(results),
                    run.getNextActions()
            );
        }
    }

    private AgentSessionRecord requireInitiatorSession(
            String sessionId,
            Long tenantId,
            Long projectId,
            String actorId) {
        AgentSessionRecord session = sessionStore.findById(sessionId)
                .orElseThrow(() -> new PlatformBusinessException(PlatformErrorCode.NOT_FOUND,
                        "Agent 会话不存在，sessionId=" + sessionId));
        if (tenantId == null || projectId == null || actorId == null || actorId.isBlank()
                || !Objects.equals(session.getTenantId(), tenantId)
                || !Objects.equals(session.getProjectId(), projectId)
                || !session.getActorId().equals(actorId.trim())) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "只有本次 Agent Run 的发起人才能在原租户和项目范围内确认执行");
        }
        return session;
    }

    private AgentRunRecord requireRun(AgentSessionRecord session, String runId) {
        return session.getRuns().stream()
                .filter(run -> run.getRunId().equals(runId))
                .findFirst()
                .orElseThrow(() -> new PlatformBusinessException(PlatformErrorCode.NOT_FOUND,
                        "Agent Run 不存在，runId=" + runId));
    }

    private void requireDelegatedIdentity(String actorRole, String authorizedProjectRoles, Long projectId) {
        if (actorRole == null || actorRole.isBlank()) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "缺少当前用户角色，Agent 不会降级为服务账号执行");
        }
        if (authorizedProjectRoles == null || authorizedProjectRoles.isBlank()
                || projectId == null
                || !containsProjectRole(authorizedProjectRoles, projectId)) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "缺少当前项目角色授权，Agent 不会代用户执行数据同步写操作");
        }
    }

    private boolean containsProjectRole(String authorizedProjectRoles, Long projectId) {
        String expectedPrefix = projectId + ":";
        return java.util.Arrays.stream(authorizedProjectRoles.split(","))
                .map(String::trim)
                .anyMatch(item -> item.regionMatches(true, 0, expectedPrefix, 0, expectedPrefix.length()));
    }

    private String normalizeComment(String comment) {
        return comment == null || comment.isBlank()
                ? "用户在智能助手中确认执行本次计划"
                : comment.trim();
    }
}
