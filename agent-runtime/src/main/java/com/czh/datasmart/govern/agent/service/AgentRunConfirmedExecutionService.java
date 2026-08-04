/**
 * @Author : Cui
 * @Date: 2026/07/10 00:00
 * @Description DataSmart Govern Backend - AgentRunConfirmedExecutionService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service;

import com.czh.datasmart.govern.agent.controller.dto.AgentRunConfirmedExecutionRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunConfirmedExecutionResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentPostConfirmContinuationRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentPostConfirmContinuationView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionAuditView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionDecisionRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionFailureView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionResultView;
import com.czh.datasmart.govern.agent.service.answer.AgentExecutionAssistantAnswer;
import com.czh.datasmart.govern.agent.service.answer.AgentExecutionResultAnswerGenerator;
import com.czh.datasmart.govern.agent.service.answer.AgentToolExecutionFailureSupport;
import com.czh.datasmart.govern.agent.service.continuation.AgentPostConfirmContinuationClient;
import com.czh.datasmart.govern.agent.service.session.AgentRunRecord;
import com.czh.datasmart.govern.agent.service.session.AgentConversationMessageRecord;
import com.czh.datasmart.govern.agent.service.session.AgentSessionStore;
import com.czh.datasmart.govern.agent.service.session.AgentSessionRecord;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.time.LocalDateTime;
import java.util.UUID;

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

    private static final String SYNC_TASK_PUBLISH = "sync.task.publish";
    private static final String SYNC_TASK_RUN = "sync.task.run";
    private static final Set<String> PUBLISH_COMPLETES_GOAL_MODES = Set.of(
            "SCHEDULED_FULL", "SCHEDULED_BATCH", "CDC_STREAMING", "REAL_TIME");

    private final AgentSessionStore sessionStore;
    private final AgentSessionService sessionService;
    private final AgentToolExecutionAuditService auditService;
    private final AgentToolExecutionResultQueryService resultQueryService;
    private final AgentExecutionResultAnswerGenerator resultAnswerGenerator;
    private final AgentPostConfirmContinuationClient continuationClient;

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
        ConfirmedBatch batch;
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
                String errorCode = result.audit().errorCode() == null
                        ? "UNCLASSIFIED_TOOL_FAILURE"
                        : result.audit().errorCode();
                String errorMessage = result.audit().message() == null
                        ? "工具未返回具体错误说明"
                        : result.audit().message();
                run.failAfterToolExecution("Agent 工具节点执行失败，toolCode=" + audit.toolCode()
                        + "，errorCode=" + errorCode + "，原因=" + errorMessage);
                break;
            }
            if (failed == 0 && succeeded == audits.size()) {
                run.completeAfterToolExecution("本轮 Agent 工具批次已全部执行成功，后续业务阶段以实际工具结果为准。");
            }
            List<AgentToolExecutionAuditView> finalAudits = auditService.listByRun(sessionId, runId);
            AgentExecutionAssistantAnswer assistantAnswer = resultAnswerGenerator.generate(
                    run.getState().name(),
                    audits.size(),
                    succeeded,
                    failed,
                    finalAudits,
                    List.copyOf(results),
                    run.getNextActions()
            );
            batch = new ConfirmedBatch(
                    run.getState().name(),
                    audits.size(),
                    succeeded,
                    failed,
                    List.copyOf(results),
                    List.copyOf(finalAudits),
                    run.getNextActions(),
                    assistantAnswer
            );
            // 工具状态推进和 Run 终态属于同一会话聚合。先持久化这一阶段，即使后续 Python 二轮回答失败，
            // 用户仍能在历史会话中看到真实工具结果，而不会退回到“尚未执行”的旧快照。
            sessionStore.save(session);
        }

        // Never call Python while holding the session monitor. Python immediately
        // submits the next ToolPlan to Java and must acquire the same session to
        // create a new Run; calling it inside synchronized(session) would deadlock.
        AgentPostConfirmContinuationView continuation = continueAfterTerminalBatch(
                session, runId, tenantId, projectId, actorId, traceId, batch
        );
        List<AgentToolExecutionFailureView> failures = AgentToolExecutionFailureSupport.failures(
                batch.finalAudits(), batch.executedResults());
        String assistantReply = resolvedAssistantReply(batch, continuation);
        synchronized (session) {
            // 二轮总结也是可继续追问的正式会话消息。它单独保存，是因为远程模型调用不能放在 session 锁内，
            // 否则 Python 回调 Java 创建下一 Run 时会等待同一把锁并形成跨服务死锁。
            session.addMessage(new AgentConversationMessageRecord(
                    "agm_" + UUID.randomUUID().toString().replace("-", ""),
                    runId, "AGENT", assistantReply, LocalDateTime.now()));
            sessionStore.save(session);
        }
        return new AgentRunConfirmedExecutionResponse(
                sessionId,
                runId,
                batch.runState(),
                batch.plannedCount(),
                batch.succeededCount(),
                batch.failedCount(),
                batch.executedResults(),
                failures,
                batch.nextActions(),
                assistantReply,
                batch.assistantAnswer().mode(),
                batch.assistantAnswer().modelProviderStatus(),
                continuation
        );
    }

    /**
     * 把本批次已经产生的成功或失败终态事实交给 Python 续跑。
     *
     * <p>失败不是“停止解释”的理由，而是下一轮诊断的输入。Python 可以基于错误码、业务说明和低敏输出选择
     * 只读日志、元数据、任务状态或 RAG 工具；任何修改草稿、表结构或数据的动作仍会形成新的审批 Run。
     * 未执行的 PLANNED 节点不会作为伪结果发送给模型，避免模型误以为后续生命周期已经发生。</p>
     */
    private AgentPostConfirmContinuationView continueAfterTerminalBatch(
            AgentSessionRecord session,
            String runId,
            Long tenantId,
            Long projectId,
            String actorId,
            String traceId,
            ConfirmedBatch batch) {
        if (batch.failedCount() == 0 && taskSubmissionBoundaryReached(batch.finalAudits())) {
            return AgentPostConfirmContinuationView.businessGoalReached();
        }
        List<AgentToolExecutionResultView> allResults = resultQueryService.listRunToolExecutionResults(
                session.getSessionId(), runId
        ).stream()
                .filter(Objects::nonNull)
                .filter(result -> result.audit() != null)
                .filter(result -> Set.of("SUCCEEDED", "FAILED", "REJECTED", "SKIPPED")
                        .contains(result.audit().state()))
                .toList();
        if (allResults.isEmpty()) {
            return AgentPostConfirmContinuationView.failed(
                    "本批次没有可交给 Agent 诊断的终态工具事实；请按 Run ID 检查审计状态。"
            );
        }
        return continuationClient.continueAfterConfirmedTools(new AgentPostConfirmContinuationRequest(
                String.valueOf(tenantId),
                String.valueOf(projectId),
                actorId,
                session.getSessionId(),
                runId,
                session.getObjective(),
                session.getWorkspaceKey(),
                traceId,
                allResults
        ));
    }

    /**
     * 失败时同时保留确定性事实摘要和模型诊断，防止模型回答过短后再次只剩一个错误码。
     */
    private String resolvedAssistantReply(
            ConfirmedBatch batch,
            AgentPostConfirmContinuationView continuation) {
        String modelReply = continuation.assistantReply();
        if (modelReply == null || modelReply.isBlank()) {
            return batch.assistantAnswer().content();
        }
        if (batch.failedCount() == 0) {
            return modelReply;
        }
        return batch.assistantAnswer().content() + "\n\nAgent 后续诊断：" + modelReply.trim();
    }

    /**
     * A sync creation request is complete when an immediate task reaches the
     * worker queue, or when a scheduled/CDC task is published to its runtime.
     * Waiting for a terminal data-transfer state would block indefinitely for
     * streaming jobs and unnecessarily hold the Agent HTTP request for large
     * offline jobs.
     */
    private boolean taskSubmissionBoundaryReached(List<AgentToolExecutionAuditView> audits) {
        if (audits == null || audits.isEmpty()) {
            return false;
        }
        boolean runSubmitted = audits.stream()
                .anyMatch(audit -> SYNC_TASK_RUN.equals(audit.toolCode()) && "SUCCEEDED".equals(audit.state()));
        if (runSubmitted) {
            return true;
        }
        return audits.stream()
                .filter(audit -> SYNC_TASK_PUBLISH.equals(audit.toolCode()) && "SUCCEEDED".equals(audit.state()))
                .map(AgentToolExecutionAuditView::planArguments)
                .filter(Objects::nonNull)
                .map(arguments -> Objects.toString(arguments.get("syncMode"), "").trim().toUpperCase(Locale.ROOT))
                .anyMatch(PUBLISH_COMPLETES_GOAL_MODES::contains);
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

    private record ConfirmedBatch(
            String runState,
            int plannedCount,
            int succeededCount,
            int failedCount,
            List<AgentToolExecutionResultView> executedResults,
            List<AgentToolExecutionAuditView> finalAudits,
            List<String> nextActions,
            AgentExecutionAssistantAnswer assistantAnswer) {
    }
}
