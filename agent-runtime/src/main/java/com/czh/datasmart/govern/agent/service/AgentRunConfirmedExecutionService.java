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
import com.czh.datasmart.govern.agent.specialist.SpecialistTurnFactService;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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

    /**
     * 一个“任务已提交或已调度”的结论必须由两类后置专业 Agent 共同给出执行事实。
     *
     * <p>PRECHECK 负责确认发布后的任务配置仍可被确定性校验，MONITOR 负责确认任务/执行资源已能被运行态读取。
     * 这里校验的是“两个角色都实际执行”，而不是它们的业务结论都必须成功；例如预检查可能发现可展示的告警，
     * 但该事实不应被伪造成从未执行。</p>
     */
    private static final Set<String> REQUIRED_POST_BRIDGE_SPECIALIST_ROLES = Set.of(
            "PRECHECK_AGENT", "MONITOR_AGENT");

    private final AgentSessionStore sessionStore;
    private final AgentSessionService sessionService;
    private final AgentToolExecutionAuditService auditService;
    private final AgentToolExecutionResultQueryService resultQueryService;
    private final AgentExecutionResultAnswerGenerator resultAnswerGenerator;
    private final AgentPostConfirmContinuationClient continuationClient;
    /**
     * PostgreSQL 专业 Agent 事实服务是“业务已达到提交边界”结论的持久化证据源。
     *
     * <p>本地轻量开发模式可以不启用 JDBC，因此 {@link SpecialistTurnFactService} 会按条件不创建 Bean。
     * 使用 {@link Optional} 不是为了降级放行：缺少持久化事实服务时，任何声称业务目标已完成的
     * continuation 都会被拒绝；普通的等待补充信息或等待用户确认流程不受影响。</p>
     */
    private final Optional<SpecialistTurnFactService> specialistTurnFactService;

    public AgentRunConfirmedExecutionResponse confirmAndExecute(
            String sessionId,
            String runId,
            AgentRunConfirmedExecutionRequest request,
            Long tenantId,
            Long applicationId,
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
            /*
             * A confirmation request is a one-way side-effect boundary.  Once
             * the Run is terminal, re-reading its audits and calling Python
             * again would duplicate tool execution, continuation Runs, and
             * specialist facts.  The stable request key is useful for tracing
             * retries, while this durable Run state is the authoritative
             * idempotency fence across memory and JDBC stores.
             */
            if (run.getState().isTerminal()) {
                throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                        "Agent Run 已经完成确认执行，不能重复消费同一执行边界；请读取现有运行结果");
            }
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
                session, runId, tenantId, applicationId, projectId, actorId, traceId, batch
        );
        continuation = verifyContinuationContract(session, applicationId, runId, batch, continuation);
        continuation = verifyContinuationRunDurability(sessionId, runId, continuation);
        List<AgentToolExecutionFailureView> failures = AgentToolExecutionFailureSupport.failures(
                batch.finalAudits(), batch.executedResults());
        String assistantReply = resolvedAssistantReply(batch, continuation);
        // 二轮总结也是可继续追问的正式会话消息，但此时绝不能再保存调用 Python 之前读取的 session 快照。
        // Python continuation 可能已经回调 Java 并在同一会话中创建下一 Run；原子追加消息只写消息表和活跃时间，
        // 从而避免旧快照通过 replaceRuns() 删除刚创建的修复 Run。
        boolean messageAppended = sessionStore.appendConversationMessage(
                sessionId,
                new AgentConversationMessageRecord(
                        "agm_" + UUID.randomUUID().toString().replace("-", ""),
                        runId, "AGENT", assistantReply, LocalDateTime.now())
        );
        if (!messageAppended) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND,
                    "Agent 会话在记录确认结果前已不存在，系统未返回可能失效的后续执行入口，sessionId=" + sessionId);
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
            Long applicationId,
            Long projectId,
            String actorId,
            String traceId,
            ConfirmedBatch batch) {
        /*
         * 即使任务已经发布或提交到 worker，也必须把本批次终态 Java 事实交给 Python：
         * - 成功路径不会再调用模型复述“任务已提交”，而是仅运行确定性的 PRECHECK/MONITOR 后置复核；
         * - 失败路径继续进入模型诊断、RAG/Recovery 和下一轮受审批 ToolPlan；
         * - 两条路径都使用同一批带 auditId/runId/outputRef 的事实，前端或模型文本不能伪造 taskId/executionId。
         *
         * 旧逻辑在 taskSubmissionBoundaryReached=true 时直接返回，虽然节省了一次 continuation HTTP，
         * 却也让提交后的专业 Agent 永远拿不到真实资源定位，durable fact 只留下审批前的“缺少任务引用”。
         */
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
                String.valueOf(applicationId),
                String.valueOf(projectId),
                actorId,
                session.getDelegation().getDelegationId(),
                session.getSessionId(),
                runId,
                session.getObjective(),
                session.getWorkspaceKey(),
                traceId,
                allResults
        ));
    }

    /**
     * 验证 Python 后确认续跑响应能否安全地影响当前 Java 会话。
     *
     * <p>Python Runtime 是受信内部服务，但仍是跨进程边界：HTTP 2xx 只证明请求被处理，不能证明返回的
     * sessionId、sourceRunId、nextRunId 或业务完成结论属于这次确认请求。该方法在响应离开 Java 控制面前
     * 建立三个 fail-closed 约束：</p>
     * <ol>
     *     <li>任何会让前端继续操作的响应，必须回指本次受治理的 session/run；</li>
     *     <li>下一 Run 不能复用当前源 Run，避免确认页面被回指到已经结束的执行批次；</li>
     *     <li>“任务已提交/已调度”的完成结论，必须同时具有本地提交边界和 PRECHECK/MONITOR 的后置执行证据。</li>
     * </ol>
     *
     * <p>验证失败不会回滚已经完成的 Java 工具动作。那些动作已经在调用 Python 前持久化并记录审计；这里只清空
     * 不可信的续跑入口，避免浏览器据此执行错误或悬空的下一步。</p>
     *
     * @param session 当前 Java 已知且已验证归属的会话聚合
     * @param applicationId Gateway 根据权限中心重建的当前产品应用范围
     * @param runId 当前 Java 已知的源 Run ID
     * @param batch 已持久化的本轮 Java 工具执行批次
     * @param continuation Python 返回的低敏续跑视图
     * @return 原始视图，或不含可执行入口的契约失败视图
     */
    private AgentPostConfirmContinuationView verifyContinuationContract(
            AgentSessionRecord session,
            Long applicationId,
            String runId,
            ConfirmedBatch batch,
            AgentPostConfirmContinuationView continuation) {
        String sessionId = session.getSessionId();
        if (continuation == null) {
            return AgentPostConfirmContinuationView.contractInvalid(
                    sessionId, runId, "EMPTY_CONTINUATION_RESPONSE");
        }
        if (!claimsActionableContinuation(continuation)) {
            // 例如 Python 不可用时的 FAILED_RETRYABLE 仅表达诊断状态，既没有 nextRun 也不会引导用户确认，
            // 不需要把本地构造的失败视图误判为“远端 locator 不一致”。
            return continuation;
        }
        if (!sameRequiredLocator(sessionId, continuation.sessionId())
                || !sameRequiredLocator(runId, continuation.sourceRunId())) {
            return AgentPostConfirmContinuationView.contractInvalid(
                    sessionId, runId, "CONTINUATION_SCOPE_MISMATCH");
        }
        if (hasText(continuation.nextRunId()) && runId.equals(continuation.nextRunId().trim())) {
            return AgentPostConfirmContinuationView.contractInvalid(
                    sessionId, runId, "NEXT_RUN_REUSES_SOURCE_RUN");
        }
        if (claimsBusinessGoalReached(continuation)
                && !hasVerifiedBusinessGoalHandoff(session, applicationId, runId, batch, continuation)) {
            return AgentPostConfirmContinuationView.contractInvalid(
                    sessionId, runId, "POST_BRIDGE_VERIFICATION_INCOMPLETE");
        }
        return continuation;
    }

    /**
     * 判断远端响应是否会让前端获得新的动作入口或显示“业务已经完成”的最终结论。
     *
     * <p>纯失败、禁用或无后续动作的响应不依赖远端 locator；一旦携带 nextRun、确认标记、继续标记或完成结论，
     * 就必须执行严格的 session/run 契约校验。这避免把内部降级响应错误地二次降级，同时对真正的可执行路径
     * 保持 fail-closed。</p>
     */
    private boolean claimsActionableContinuation(AgentPostConfirmContinuationView continuation) {
        return hasText(continuation.nextRunId())
                || Boolean.TRUE.equals(continuation.continued())
                || Boolean.TRUE.equals(continuation.requiresConfirmation())
                || claimsBusinessGoalReached(continuation);
    }

    /**
     * 判断远端响应是否声称当前同步目标已经到达异步提交边界。
     *
     * <p>Python 当前会同时返回 {@code BUSINESS_GOAL_REACHED} 与 {@code TASK_SUBMITTED_OR_SCHEDULED}。
     * 这里将任一标识视为需要验证的高风险声明，防止其中一个字段被遗漏后绕过后置 PRECHECK/MONITOR 证据。</p>
     */
    private boolean claimsBusinessGoalReached(AgentPostConfirmContinuationView continuation) {
        return "BUSINESS_GOAL_REACHED".equals(normalizeCode(continuation.status()))
                || "TASK_SUBMITTED_OR_SCHEDULED".equals(normalizeCode(continuation.stoppedReason()));
    }

    /**
     * 验证“业务目标已完成”结论的本地提交边界和专业 Agent 证据。
     *
     * <p>首先必须是一个真正的终态响应，而不是 {@code CONTINUED + TASK_SUBMITTED_OR_SCHEDULED} 这样的矛盾组合；
     * 其次 Java 本轮不能有工具失败，并且审计中必须存在已成功的立即运行或定期/CDC 发布动作。最后必须从
     * PostgreSQL 专业 Agent 事实表读取同一租户、应用、项目、用户、会话、Run 与委托链的 PRECHECK/MONITOR
     * 成功证据。Python 返回的 {@code postBridgeVerification} 仅供前端展示，永远不作为授权或完成证据。</p>
     */
    private boolean hasVerifiedBusinessGoalHandoff(
            AgentSessionRecord session,
            Long applicationId,
            String runId,
            ConfirmedBatch batch,
            AgentPostConfirmContinuationView continuation) {
        boolean terminalShape = "BUSINESS_GOAL_REACHED".equals(normalizeCode(continuation.status()))
                && "TASK_SUBMITTED_OR_SCHEDULED".equals(normalizeCode(continuation.stoppedReason()))
                && !Boolean.TRUE.equals(continuation.continued())
                && !Boolean.TRUE.equals(continuation.requiresConfirmation())
                && !hasText(continuation.nextRunId());
        if (!terminalShape || batch == null || batch.failedCount() != 0
                || !taskSubmissionBoundaryReached(batch.finalAudits())) {
            return false;
        }
        if (session == null || applicationId == null || applicationId <= 0 || !hasText(runId)
                || session.getDelegation() == null || !hasText(session.getDelegation().getDelegationId())) {
            return false;
        }
        return specialistTurnFactService
                .map(service -> service.hasTerminalSuccessfulEvidenceForRoles(
                        session.getTenantId(),
                        applicationId,
                        session.getProjectId(),
                        session.getActorId(),
                        session.getSessionId(),
                        runId,
                        session.getDelegation().getDelegationId(),
                        REQUIRED_POST_BRIDGE_SPECIALIST_ROLES
                ))
                .orElse(false);
    }

    /** 精确比较受治理定位符；空 Header/字段绝不被解释为可匹配。 */
    private boolean sameRequiredLocator(String expected, String actual) {
        return hasText(expected) && hasText(actual) && expected.equals(actual.trim());
    }

    /** 规范化固定的协议码和角色码；空值保留为空，调用方据此 fail-closed。 */
    private String normalizeCode(Object value) {
        return value == null ? "" : value.toString().trim().toUpperCase(Locale.ROOT);
    }

    /** 统一处理可选短文本，避免空白 nextRunId 或 locator 进入“可执行”判断。 */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 在响应离开 Java 控制面之前确认 Python 声明的下一 Run 已经真实进入 durable store。
     *
     * <p>本方法只在 {@link #verifyContinuationContract(String, String, ConfirmedBatch, AgentPostConfirmContinuationView)}
     * 已验证 session/run 责任链之后执行。远程 continuation 的 HTTP 成功不等价于下一 Run 一定持久化成功；重新读取
     * 会话可以覆盖 JDBC 提交失败、回调响应与事务提交竞态以及未来存储实现的异常。如果 Run 不存在，返回一个保留
     * 修复建议但不含 {@code nextRunId} 的可重试结果，让前端提供“重新生成审核计划”，而不是展示必然失败的确认按钮。</p>
     *
     * @param sessionId 当前受治理会话 ID
     * @param runId 当前源 Run，用于空响应时构造安全失败视图
     * @param continuation Python 返回且已通过 locator 契约的续跑视图
     * @return 已验证的原视图，或去除悬空 Run 引用的可重试视图
     */
    private AgentPostConfirmContinuationView verifyContinuationRunDurability(
            String sessionId,
            String runId,
            AgentPostConfirmContinuationView continuation) {
        if (continuation == null) {
            return AgentPostConfirmContinuationView.contractInvalid(
                    sessionId, runId, "EMPTY_CONTINUATION_RESPONSE");
        }
        if (!hasText(continuation.nextRunId())) {
            return continuation;
        }
        boolean durableRunExists = sessionStore.findById(sessionId)
                .stream()
                .flatMap(storedSession -> storedSession.getRuns().stream())
                .anyMatch(run -> continuation.nextRunId().trim().equals(run.getRunId()));
        return durableRunExists
                ? continuation
                : AgentPostConfirmContinuationView.nextRunNotDurable(continuation);
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
