/**
 * @Author : Cui
 * @Date: 2026/05/29 22:08
 * @Description DataSmart Govern Backend - AgentRunToolAutoExecutionService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.execution;

import com.czh.datasmart.govern.agent.config.AgentRuntimeProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolAutoExecutionItemView;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolAutoExecutionRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolAutoExecutionResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolExecutionPolicyItemView;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolExecutionPolicyView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionResultView;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionState;
import com.czh.datasmart.govern.agent.model.AgentToolRiskLevel;
import com.czh.datasmart.govern.agent.service.AgentToolExecutionService;
import com.czh.datasmart.govern.agent.service.session.AgentRunRecord;
import com.czh.datasmart.govern.agent.service.session.AgentSessionMemoryStore;
import com.czh.datasmart.govern.agent.service.session.AgentSessionRecord;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Run 级受控同步工具自动执行服务。
 *
 * <p>这是 4.41 policy preflight 之后的第一阶段执行闭环：只执行“低风险、只读、幂等、同步、参数完整、无需审批”的工具。
 * 它仍然不是完整的 Agent 自动驾驶系统，原因是商业化产品里的工具执行还要面对：
 * 审批权限、租户配额、工具健康熔断、队列容量、依赖 DAG、失败回滚、幂等键、审计告警和多实例并发竞争。</p>
 *
 * <p>当前服务的核心原则：
 * 1. 先读 policy，再按更严格的自动执行条件二次筛选；
 * 2. 只在同步短耗时工具上执行，异步任务留给 task-management/Kafka worker；
 * 3. 单次批次有数量上限，避免一个 HTTP 请求无限制占用资源；
 * 4. 支持 dryRun，让前端或 Python Runtime 先看到候选与跳过原因；
 * 5. 批次中某个工具失败不直接中断整个响应，而是记录到 item，方便调用方做二轮解释或人工复核。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentRunToolAutoExecutionService {

    private static final int DEFAULT_MAX_SYNC_AUTO_EXECUTIONS = 5;

    private final AgentRuntimeProperties properties;
    private final AgentSessionMemoryStore sessionMemoryStore;
    private final AgentRunToolExecutionPolicyService policyService;
    private final AgentToolExecutionService toolExecutionService;

    /**
     * 执行当前 Run 中满足条件的同步工具候选。
     *
     * <p>该方法会在会话对象上加锁。当前 agent-runtime 仍有内存会话与审计仓储，缺少数据库条件更新和分布式锁；
     * 因此同一会话内需要串行化自动执行批次，避免两个请求同时把同一个 PLANNED 工具推进到 EXECUTING。</p>
     *
     * @param sessionId Agent 会话 ID。
     * @param runId Agent Run ID。
     * @param request 批次约束，可为空。
     * @param traceId 链路追踪 ID，会透传给真实工具执行服务。
     * @return 批次执行响应，包含执行前 policy 和每个工具的处理结果。
     */
    public AgentRunToolAutoExecutionResponse executeEligibleSyncTools(String sessionId,
                                                                      String runId,
                                                                      AgentRunToolAutoExecutionRequest request,
                                                                      String traceId) {
        ensureAutoExecutionEnabled();
        AgentSessionRecord session = requireSession(sessionId);
        synchronized (session) {
            AgentRunRecord run = requireRun(session, runId);
            AgentRunToolExecutionPolicyView policy = policyService.inspectRunPolicy(sessionId, runId);
            AutoExecutionBatch batch = executeBatch(session, run, policy, normalizeRequest(request), traceId);
            return new AgentRunToolAutoExecutionResponse(
                    sessionId,
                    runId,
                    batch.dryRun(),
                    batch.requestedLimit(),
                    batch.effectiveLimit(),
                    batch.executedCount(),
                    batch.failedCount(),
                    batch.skippedCount(),
                    policy,
                    batch.items()
            );
        }
    }

    /**
     * 执行批次主体。
     *
     * <p>这里把“是否候选”和“是否真正执行”拆成两个步骤：
     * policy 的 AUTO_EXECUTABLE 表示工具从控制面状态看可以执行；
     * 自动执行器还会进一步要求 LOW、readOnly、idempotent、requiresApproval=false。
     * 这种更严格的二次筛选是为了避免未来 policy 放宽后，自动执行入口不受控地扩大执行范围。</p>
     */
    private AutoExecutionBatch executeBatch(AgentSessionRecord session,
                                            AgentRunRecord run,
                                            AgentRunToolExecutionPolicyView policy,
                                            NormalizedAutoExecutionRequest request,
                                            String traceId) {
        AutoExecutionBatchBuilder builder = new AutoExecutionBatchBuilder(request.dryRun(), request.requestedLimit(), request.effectiveLimit());
        for (AgentRunToolExecutionPolicyItemView item : policy.items()) {
            if (!request.selectedAuditIds().isEmpty() && !request.selectedAuditIds().contains(item.auditId())) {
                builder.skipped(skip(item, "NOT_SELECTED", "调用方 auditIds 白名单未包含该工具，本批次不处理。"));
                continue;
            }
            Eligibility eligibility = inspectEligibility(item);
            if (!eligibility.allowed()) {
                builder.skipped(skip(item, "SKIPPED", eligibility.reason()));
                continue;
            }
            if (!builder.canExecuteMore()) {
                builder.skipped(skip(item, "BATCH_LIMIT_REACHED", "已达到本次同步自动执行数量上限，剩余候选留待下一批处理。"));
                continue;
            }
            if (request.dryRun()) {
                builder.skipped(new AgentRunToolAutoExecutionItemView(
                        item.auditId(),
                        item.toolCode(),
                        item.decision(),
                        "DRY_RUN_CANDIDATE",
                        "dryRun=true，仅演算该工具可被自动执行，不推进审计状态，也不调用下游适配器。",
                        null,
                        item.reasons()
                ));
                builder.consumeSlot();
                continue;
            }
            executeOne(session, run, item, traceId, builder);
        }
        return builder.build();
    }

    /**
     * 执行单个工具候选。
     *
     * <p>真实执行仍委托 {@link AgentToolExecutionService}。自动执行服务不重复实现适配器选择、审计状态推进、
     * 输出保存和失败落审计等逻辑，避免形成第二套状态机。这里仅负责批次层面的错误隔离和计数。</p>
     */
    private void executeOne(AgentSessionRecord session,
                            AgentRunRecord run,
                            AgentRunToolExecutionPolicyItemView item,
                            String traceId,
                            AutoExecutionBatchBuilder builder) {
        builder.consumeSlot();
        try {
            AgentToolExecutionResultView result = toolExecutionService.execute(session, run, item.auditId(), traceId);
            if (AgentToolExecutionState.SUCCEEDED.name().equals(result.audit().state())) {
                builder.executed(new AgentRunToolAutoExecutionItemView(
                        item.auditId(),
                        item.toolCode(),
                        item.decision(),
                        "EXECUTED",
                        "工具已通过受控同步自动执行器执行成功。",
                        result,
                        item.reasons()
                ));
                return;
            }
            builder.failed(new AgentRunToolAutoExecutionItemView(
                    item.auditId(),
                    item.toolCode(),
                    item.decision(),
                    "FAILED",
                    "工具已尝试执行，但最终状态不是 SUCCEEDED，调用方应读取 result.audit.errorCode/message 判断原因。",
                    result,
                    item.reasons()
            ));
        } catch (RuntimeException exception) {
            builder.failed(new AgentRunToolAutoExecutionItemView(
                    item.auditId(),
                    item.toolCode(),
                    item.decision(),
                    "EXECUTION_REJECTED",
                    "执行入口拒绝或发生异常，可能是状态已变化、Run 已终态、工具缺少适配器或二次守卫未通过：" + exception.getMessage(),
                    null,
                    item.reasons()
            ));
        }
    }

    /**
     * 自动执行器的额外安全筛选。
     *
     * <p>这里比 policy 更保守：只有 LOW 风险、只读、幂等、无需审批、policy 决策为 AUTO_EXECUTABLE 的工具才允许自动执行。
     * MEDIUM 虽然可能只是建议生成，但仍可能涉及较多业务上下文，建议先保留给人工触发或后续租户策略开关。</p>
     */
    private Eligibility inspectEligibility(AgentRunToolExecutionPolicyItemView item) {
        if (!AgentRunToolExecutionDecision.AUTO_EXECUTABLE.name().equals(item.decision()) || !Boolean.TRUE.equals(item.autoExecutable())) {
            return Eligibility.rejected("policy 未将该工具标记为 AUTO_EXECUTABLE，本批次不会自动执行。");
        }
        if (!AgentToolRiskLevel.LOW.name().equals(normalize(item.riskLevel()))) {
            return Eligibility.rejected("当前第一阶段只允许 LOW 风险工具自动执行，其他风险等级需要人工触发或后续租户策略放开。");
        }
        if (!Boolean.TRUE.equals(item.readOnly())) {
            return Eligibility.rejected("当前第一阶段只允许只读工具自动执行，写操作必须走审批或人工执行。");
        }
        if (Boolean.TRUE.equals(item.requiresApproval())) {
            return Eligibility.rejected("工具显式 requiresApproval=true，不能通过自动执行入口绕过审批。");
        }
        if (!Boolean.TRUE.equals(item.idempotent())) {
            return Eligibility.rejected("工具未声明幂等，自动执行失败后难以安全重试或恢复。");
        }
        return Eligibility.allow();
    }

    private AgentRunToolAutoExecutionItemView skip(AgentRunToolExecutionPolicyItemView item, String action, String reason) {
        return new AgentRunToolAutoExecutionItemView(
                item.auditId(),
                item.toolCode(),
                item.decision(),
                action,
                reason,
                null,
                item.reasons()
        );
    }

    private NormalizedAutoExecutionRequest normalizeRequest(AgentRunToolAutoExecutionRequest request) {
        boolean dryRun = request != null && Boolean.TRUE.equals(request.dryRun());
        int configuredLimit = properties.getMaxSyncAutoExecutionsPerRun() == null
                ? DEFAULT_MAX_SYNC_AUTO_EXECUTIONS
                : Math.max(1, properties.getMaxSyncAutoExecutionsPerRun());
        int requestedLimit = request == null || request.maxExecutions() == null
                ? configuredLimit
                : Math.max(0, request.maxExecutions());
        int effectiveLimit = Math.min(configuredLimit, requestedLimit);
        Set<String> selectedAuditIds = request == null || request.auditIds() == null
                ? Set.of()
                : new HashSet<>(request.auditIds());
        return new NormalizedAutoExecutionRequest(dryRun, requestedLimit, effectiveLimit, selectedAuditIds);
    }

    private void ensureAutoExecutionEnabled() {
        if (!Boolean.TRUE.equals(properties.getEnabled())) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT, "Agent Runtime 当前未启用");
        }
        if (!Boolean.TRUE.equals(properties.getSyncAutoExecutionEnabled())) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "Agent 同步自动执行入口当前未启用");
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

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private record NormalizedAutoExecutionRequest(boolean dryRun,
                                                  int requestedLimit,
                                                  int effectiveLimit,
                                                  Set<String> selectedAuditIds) {
    }

    private record Eligibility(boolean allowed, String reason) {

        static Eligibility allow() {
            return new Eligibility(true, "");
        }

        static Eligibility rejected(String reason) {
            return new Eligibility(false, reason);
        }
    }

    private record AutoExecutionBatch(boolean dryRun,
                                      int requestedLimit,
                                      int effectiveLimit,
                                      int executedCount,
                                      int failedCount,
                                      int skippedCount,
                                      List<AgentRunToolAutoExecutionItemView> items) {
    }

    private static class AutoExecutionBatchBuilder {

        private final boolean dryRun;
        private final int requestedLimit;
        private final int effectiveLimit;
        private final java.util.ArrayList<AgentRunToolAutoExecutionItemView> items = new java.util.ArrayList<>();
        private int consumedSlots;
        private int executedCount;
        private int failedCount;
        private int skippedCount;

        private AutoExecutionBatchBuilder(boolean dryRun, int requestedLimit, int effectiveLimit) {
            this.dryRun = dryRun;
            this.requestedLimit = requestedLimit;
            this.effectiveLimit = effectiveLimit;
        }

        private boolean canExecuteMore() {
            return consumedSlots < effectiveLimit;
        }

        private void consumeSlot() {
            consumedSlots++;
        }

        private void executed(AgentRunToolAutoExecutionItemView item) {
            executedCount++;
            items.add(item);
        }

        private void failed(AgentRunToolAutoExecutionItemView item) {
            failedCount++;
            items.add(item);
        }

        private void skipped(AgentRunToolAutoExecutionItemView item) {
            skippedCount++;
            items.add(item);
        }

        private AutoExecutionBatch build() {
            return new AutoExecutionBatch(
                    dryRun,
                    requestedLimit,
                    effectiveLimit,
                    executedCount,
                    failedCount,
                    skippedCount,
                    List.copyOf(items)
            );
        }
    }
}
