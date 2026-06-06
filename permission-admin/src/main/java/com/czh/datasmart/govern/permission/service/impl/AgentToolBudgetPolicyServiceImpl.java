/**
 * @Author : Cui
 * @Date: 2026/06/02 18:38
 * @Description DataSmartGovernBackend - AgentToolBudgetPolicyServiceImpl.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service.impl;

import com.czh.datasmart.govern.permission.controller.dto.AgentToolBudgetPolicyEvaluateRequest;
import com.czh.datasmart.govern.permission.controller.dto.AgentToolBudgetPolicyView;
import com.czh.datasmart.govern.permission.controller.dto.AgentToolExecutionReadinessPolicyView;
import com.czh.datasmart.govern.permission.service.AgentToolBudgetPolicyService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Agent 工具调用预算策略服务第一版实现。
 *
 * <p>当前实现刻意不直接访问数据库，因为本阶段目标是先稳定 Java 控制面契约：
 * 1. Python Runtime 需要知道以后从哪里拿预算；
 * 2. gateway 和管理后台需要知道预算策略长什么样；
 * 3. permission-admin 需要先沉淀“角色、套餐、风险、backlog 如何影响预算”的业务语义。</p>
 *
 * <p>后续生产化路线：
 * - 把角色基线迁移到策略表；
 * - 把 tenantPlanCode 接入租户套餐中心；
 * - 把 workerBacklogLevel 接入 task-management/data-sync/data-quality 指标；
 * - 把 policyVersion 换成正式策略发布版本；
 * - 把每次评估写入低敏审计或指标。</p>
 */
@Service
public class AgentToolBudgetPolicyServiceImpl implements AgentToolBudgetPolicyService {

    @Override
    public AgentToolBudgetPolicyView evaluate(AgentToolBudgetPolicyEvaluateRequest request) {
        List<String> notes = new ArrayList<>();
        Budget budget = roleBaseline(normalize(request.getActorRole()), notes);
        applyTenantPlanLimit(budget, normalize(request.getTenantPlanCode()), notes);
        applyWorkspaceRisk(budget, normalize(request.getWorkspaceRiskLevel()), notes);
        applyWorkerBacklog(budget, normalize(request.getWorkerBacklogLevel()), notes);
        applyRequestedToolRisk(budget, normalize(request.getRequestedToolRiskLevel()), notes);
        budget.sanitize();

        Map<String, Integer> toolCallBudget = new LinkedHashMap<>();
        toolCallBudget.put("maxProposedToolCalls", budget.maxProposedToolCalls);
        toolCallBudget.put("maxAutoExecutableToolCalls", budget.maxAutoExecutableToolCalls);
        toolCallBudget.put("maxHighRiskToolCalls", budget.maxHighRiskToolCalls);
        toolCallBudget.put("maxSingleArgumentsBytes", budget.maxSingleArgumentsBytes);
        toolCallBudget.put("maxTotalArgumentsBytes", budget.maxTotalArgumentsBytes);
        AgentToolExecutionReadinessPolicyView readinessPolicy = buildReadinessPolicy(request, budget, notes);

        return new AgentToolBudgetPolicyView(
                true,
                "IN_MEMORY_RULE",
                policyVersion(request),
                toolCallBudget,
                readinessPolicy,
                List.copyOf(notes),
                recommendedActions(budget, notes)
        );
    }

    /**
     * 构建 Python Runtime 可直接注入的工具执行准备度策略。
     *
     * <p>这里没有简单复用 `toolCallBudget` map，是因为 readiness 策略需要表达更多执行前治理语义：
     * 高风险是否审批、CRITICAL 是否阻断、草案是否允许、策略影响码是什么。把这些内容放进独立 DTO，
     * 可以让 gateway/agent-runtime 后续直接把它注入到
     * `trustedControlPlane.toolExecutionReadinessPolicy`，而不需要 Python 继续从旧预算字段里猜测。</p>
     */
    private AgentToolExecutionReadinessPolicyView buildReadinessPolicy(
            AgentToolBudgetPolicyEvaluateRequest request,
            Budget budget,
            List<String> notes) {
        String actorRole = normalizeRole(request.getActorRole());
        String tenantPlanCode = normalizeOrDefault(request.getTenantPlanCode(), "STANDARD");
        String workspaceRiskLevel = normalizeOrDefault(request.getWorkspaceRiskLevel(), "NORMAL");
        String workerBacklogLevel = normalizeOrDefault(request.getWorkerBacklogLevel(), "NORMAL");
        String requestedToolRiskLevel = normalizeOrDefault(request.getRequestedToolRiskLevel(), "LOW");
        List<String> influenceCodes = readinessInfluenceCodes(actorRole, tenantPlanCode, workspaceRiskLevel,
                workerBacklogLevel, requestedToolRiskLevel, notes);
        return new AgentToolExecutionReadinessPolicyView(
                "permission-admin",
                policyVersion(request),
                actorRole,
                tenantPlanCode,
                workspaceRiskLevel,
                workerBacklogLevel,
                budget.maxAutoExecutableToolCalls,
                asyncToolBudget(budget, workerBacklogLevel),
                true,
                true,
                allowDraftWithoutAllParameters(workspaceRiskLevel, workerBacklogLevel),
                influenceCodes
        );
    }

    /**
     * 推导异步工具准备度预算。
     *
     * <p>旧的 `toolCallBudget` 主要关注模型提议和同步自动执行数量，没有直接表达“异步入队预算”。
     * readiness 策略必须显式给出 `maxAsyncTools`，否则 Python 侧只能使用本地默认值。这里用
     * `maxAutoExecutableToolCalls` 做保守基线，再根据 backlog 收紧，避免 worker 已经积压时继续扩大队列。</p>
     */
    private int asyncToolBudget(Budget budget, String workerBacklogLevel) {
        int baseline = Math.max(0, Math.min(2, budget.maxAutoExecutableToolCalls));
        if ("CRITICAL".equals(workerBacklogLevel)) {
            return 0;
        }
        if ("HIGH".equals(workerBacklogLevel)) {
            return Math.min(1, baseline);
        }
        return baseline;
    }

    /**
     * 判断是否允许参数不完整的工具停留在草案展示阶段。
     *
     * <p>普通场景下，草案展示是有价值的：用户能看到 Agent 打算做什么，再补充参数或审批。
     * 但在 CRITICAL 风险或 CRITICAL backlog 下，继续生成草案可能造成误导，甚至让用户误以为系统已经
     * 准备好执行。因此这些场景会关闭草案容忍。</p>
     */
    private boolean allowDraftWithoutAllParameters(String workspaceRiskLevel, String workerBacklogLevel) {
        return !"CRITICAL".equals(workspaceRiskLevel) && !"CRITICAL".equals(workerBacklogLevel);
    }

    /**
     * 生成稳定的策略影响码。
     *
     * <p>notes 适合给人读，influenceCodes 适合给机器聚合。Java projection、Python runtime event、
     * 前端治理卡片和审计报表都应优先依赖 code，而不是解析中文说明。</p>
     */
    private List<String> readinessInfluenceCodes(
            String actorRole,
            String tenantPlanCode,
            String workspaceRiskLevel,
            String workerBacklogLevel,
            String requestedToolRiskLevel,
            List<String> notes) {
        List<String> codes = new ArrayList<>();
        if ("AUDITOR".equals(actorRole)) {
            codes.add("READ_ONLY_ROLE_LIMITS_AUTO_EXECUTION");
        }
        if ("FREE".equals(tenantPlanCode) || "COMMUNITY".equals(tenantPlanCode)) {
            codes.add("TENANT_PLAN_LIMITS_TOOL_BUDGET");
        }
        if ("HIGH".equals(workspaceRiskLevel)) {
            codes.add("WORKSPACE_RISK_REQUIRES_APPROVAL");
        }
        if ("CRITICAL".equals(workspaceRiskLevel)) {
            codes.add("WORKSPACE_RISK_REDUCES_TOOL_BUDGET");
        }
        if ("HIGH".equals(workerBacklogLevel)) {
            codes.add("WORKER_BACKLOG_REDUCES_TOOL_BUDGET");
        }
        if ("CRITICAL".equals(workerBacklogLevel)) {
            codes.add("WORKER_BACKLOG_BLOCKS_TOOL_BUDGET");
        }
        if ("HIGH".equals(requestedToolRiskLevel) || "CRITICAL".equals(requestedToolRiskLevel)) {
            codes.add("REQUESTED_TOOL_RISK_REQUIRES_APPROVAL");
        }
        if (codes.isEmpty() && notes.isEmpty()) {
            codes.add("DEFAULT_PERMISSION_ADMIN_READINESS_POLICY");
        }
        if (codes.isEmpty()) {
            codes.add("ROLE_AND_PLAN_BASELINE_POLICY");
        }
        return List.copyOf(codes);
    }

    /**
     * 根据角色生成基础预算。
     *
     * <p>角色基线表达的是“这个主体通常能自动推进多大动作”。
     * 审计员默认最保守，平台管理员最宽，服务账号保持中等偏保守，避免机器身份被误用为无限预算。</p>
     */
    private Budget roleBaseline(String actorRole, List<String> notes) {
        return switch (actorRole) {
            case "AUDITOR" -> note(new Budget(5, 1, 0, 16_384, 32_768), notes, "审计员默认只允许少量只读工具。");
            case "ORDINARY_USER" -> note(new Budget(5, 2, 0, 16_384, 48_000), notes, "普通用户默认不允许自动推进高风险工具。");
            case "PROJECT_OWNER", "OPERATOR" -> note(new Budget(8, 3, 1, 32_768, 65_536), notes, "项目负责人/运营人员使用标准项目级工具预算。");
            case "TENANT_ADMINISTRATOR" -> note(new Budget(10, 4, 1, 32_768, 96_000), notes, "租户管理员允许更高的管理类工具预算。");
            case "PLATFORM_ADMINISTRATOR" -> note(new Budget(12, 5, 2, 65_536, 128_000), notes, "平台管理员使用平台级高预算，但仍受风险和 backlog 收紧。");
            case "SERVICE_ACCOUNT" -> note(new Budget(6, 3, 1, 32_768, 65_536), notes, "服务账号采用中等预算，避免机器身份无限放大工具调用。");
            default -> note(new Budget(5, 2, 0, 16_384, 48_000), notes, "未知角色按普通用户保守预算处理。");
        };
    }

    /**
     * 按租户套餐收敛预算上限。
     *
     * <p>套餐限制使用 min 而不是覆盖，是为了保证角色风险不会被套餐放大。
     * 例如普通用户即使在 ENTERPRISE 套餐下，也不应自动获得平台管理员级别预算。</p>
     */
    private void applyTenantPlanLimit(Budget budget, String tenantPlanCode, List<String> notes) {
        switch (tenantPlanCode) {
            case "FREE", "COMMUNITY" -> {
                budget.cap(4, 1, 0, 16_384, 32_768);
                notes.add("免费/社区套餐触发低预算上限。");
            }
            case "ENTERPRISE" -> {
                budget.raiseCeiling(2, 1, 0, 0, 32_000);
                notes.add("企业套餐允许在角色基线基础上小幅放宽。");
            }
            case "PLATFORM_INTERNAL" -> {
                budget.raiseCeiling(4, 2, 1, 32_768, 64_000);
                notes.add("平台内部套餐允许更宽预算，仍受风险与 backlog 约束。");
            }
            default -> notes.add("未指定或未知套餐，按 STANDARD 默认预算处理。");
        }
    }

    /**
     * 按 workspace 风险等级收紧预算。
     */
    private void applyWorkspaceRisk(Budget budget, String riskLevel, List<String> notes) {
        switch (riskLevel) {
            case "HIGH" -> {
                budget.capAutoAndRisk(2, 0);
                notes.add("高风险 workspace 收紧自动推进数量并禁止自动高风险工具。");
            }
            case "CRITICAL" -> {
                budget.cap(4, 1, 0, 12_288, 24_576);
                notes.add("极高风险 workspace 使用强收紧预算。");
            }
            case "LOW" -> notes.add("低风险 workspace 不额外收紧预算。");
            default -> notes.add("普通 workspace 使用角色和套餐预算。");
        }
    }

    /**
     * 按 worker backlog 收紧预算。
     *
     * <p>该规则是容量保护的控制面入口。后续 backlogLevel 可由 task-management、data-sync 或 data-quality
     * 的队列长度、最老待处理年龄、失败重试数等指标计算得到。</p>
     */
    private void applyWorkerBacklog(Budget budget, String backlogLevel, List<String> notes) {
        switch (backlogLevel) {
            case "HIGH" -> {
                budget.capAutoAndRisk(2, budget.maxHighRiskToolCalls);
                notes.add("worker backlog 较高，临时收紧自动推进数量。");
            }
            case "CRITICAL" -> {
                budget.cap(3, 1, 0, 12_288, 24_576);
                notes.add("worker backlog 极高，只允许极小批次继续推进。");
            }
            default -> notes.add("worker backlog 未报告高压，不额外收紧。");
        }
    }

    /**
     * 按本轮最高工具风险做温和收紧。
     */
    private void applyRequestedToolRisk(Budget budget, String riskLevel, List<String> notes) {
        if ("CRITICAL".equals(riskLevel)) {
            budget.capAutoAndRisk(1, 0);
            notes.add("本轮包含 CRITICAL 工具风险，默认要求人工确认后再推进。");
        } else if ("HIGH".equals(riskLevel)) {
            budget.capAutoAndRisk(Math.min(budget.maxAutoExecutableToolCalls, 2), 1);
            notes.add("本轮包含 HIGH 工具风险，限制高风险工具数量。");
        }
    }

    private List<String> recommendedActions(Budget budget, List<String> notes) {
        List<String> actions = new ArrayList<>();
        if (budget.maxAutoExecutableToolCalls <= 1) {
            actions.add("建议把本轮 Agent 工具计划拆成更小批次，并优先走人工确认。");
        }
        if (notes.stream().anyMatch(note -> note.contains("backlog 较高") || note.contains("backlog 极高"))) {
            actions.add("建议等待 worker backlog 下降，或由运营人员临时调整租户工具预算。");
        }
        if (actions.isEmpty()) {
            actions.add("可以把 toolCallBudget 注入 Python Runtime 请求变量继续执行。");
        }
        return List.copyOf(actions);
    }

    private Budget note(Budget budget, List<String> notes, String note) {
        notes.add(note);
        return budget;
    }

    private String policyVersion(AgentToolBudgetPolicyEvaluateRequest request) {
        return "agent-tool-budget:v1:"
                + normalize(request.getActorRole()) + ":"
                + normalize(request.getTenantPlanCode()) + ":"
                + normalize(request.getWorkspaceRiskLevel()) + ":"
                + normalize(request.getWorkerBacklogLevel()) + ":"
                + normalize(request.getRequestedToolRiskLevel());
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? "STANDARD" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeRole(String value) {
        return value == null || value.isBlank() ? "ORDINARY_USER" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * 内部预算模型。
     *
     * <p>这里不用公开 DTO 承载中间状态，是为了避免 Controller 或测试误依赖内部计算过程。
     * 对外稳定契约只有 `AgentToolBudgetPolicyView.toolCallBudget`。</p>
     */
    private static final class Budget {
        private int maxProposedToolCalls;
        private int maxAutoExecutableToolCalls;
        private int maxHighRiskToolCalls;
        private int maxSingleArgumentsBytes;
        private int maxTotalArgumentsBytes;

        private Budget(int proposed, int autoExecutable, int highRisk, int singleBytes, int totalBytes) {
            this.maxProposedToolCalls = proposed;
            this.maxAutoExecutableToolCalls = autoExecutable;
            this.maxHighRiskToolCalls = highRisk;
            this.maxSingleArgumentsBytes = singleBytes;
            this.maxTotalArgumentsBytes = totalBytes;
        }

        private void cap(int proposed, int autoExecutable, int highRisk, int singleBytes, int totalBytes) {
            this.maxProposedToolCalls = Math.min(this.maxProposedToolCalls, proposed);
            this.maxAutoExecutableToolCalls = Math.min(this.maxAutoExecutableToolCalls, autoExecutable);
            this.maxHighRiskToolCalls = Math.min(this.maxHighRiskToolCalls, highRisk);
            this.maxSingleArgumentsBytes = Math.min(this.maxSingleArgumentsBytes, singleBytes);
            this.maxTotalArgumentsBytes = Math.min(this.maxTotalArgumentsBytes, totalBytes);
        }

        private void capAutoAndRisk(int autoExecutable, int highRisk) {
            this.maxAutoExecutableToolCalls = Math.min(this.maxAutoExecutableToolCalls, autoExecutable);
            this.maxHighRiskToolCalls = Math.min(this.maxHighRiskToolCalls, highRisk);
        }

        private void raiseCeiling(int proposed, int autoExecutable, int highRisk, int singleBytes, int totalBytes) {
            this.maxProposedToolCalls += proposed;
            this.maxAutoExecutableToolCalls += autoExecutable;
            this.maxHighRiskToolCalls += highRisk;
            this.maxSingleArgumentsBytes += singleBytes;
            this.maxTotalArgumentsBytes += totalBytes;
        }

        private void sanitize() {
            this.maxProposedToolCalls = Math.max(1, this.maxProposedToolCalls);
            this.maxAutoExecutableToolCalls = Math.max(1, Math.min(this.maxAutoExecutableToolCalls, this.maxProposedToolCalls));
            this.maxHighRiskToolCalls = Math.max(0, Math.min(this.maxHighRiskToolCalls, this.maxAutoExecutableToolCalls));
            this.maxSingleArgumentsBytes = Math.max(1024, this.maxSingleArgumentsBytes);
            this.maxTotalArgumentsBytes = Math.max(this.maxSingleArgumentsBytes, this.maxTotalArgumentsBytes);
        }
    }
}
