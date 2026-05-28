/**
 * @Author : Cui
 * @Date: 2026/05/14 19:05
 * @Description DataSmart Govern Backend - AgentRunStateCoordinator.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.session;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionAuditView;
import com.czh.datasmart.govern.agent.controller.dto.StartAgentRunRequest;
import com.czh.datasmart.govern.agent.model.AgentRunState;
import com.czh.datasmart.govern.agent.model.ModelWorkloadType;
import com.czh.datasmart.govern.agent.service.AgentToolExecutionAuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent Run 状态协调器。
 *
 * <p>该类专门负责“Run 状态”和“工具审计状态”之间的协调，避免把所有状态机规则继续堆在
 * `AgentSessionService` 中。这样做有三个好处：
 * 1. 会话服务只保留创建会话、绑定工具、创建 Run、取消 Run 等入口编排职责；
 * 2. Run 状态恢复、拒绝、下一步提示等规则集中在一个更小的类里，后续更容易测试和扩展；
 * 3. 当真实工具执行客户端上线时，可以继续在这里增加状态回收逻辑，而不让单个 Service 文件超过 500 行。
 *
 * <p>当前仍是内存态控制面协调。后续持久化到数据库后，这些规则应被事务或乐观锁保护，
 * 保证审批回调、工具执行回调和用户取消操作并发发生时不会互相覆盖。
 */
@Component
@RequiredArgsConstructor
public class AgentRunStateCoordinator {

    private final AgentToolExecutionAuditService toolExecutionAuditService;

    /**
     * 判断当前会话是否绑定了必须人工确认的工具。
     *
     * <p>这里不信任前端请求体，而是复用工具目录继承下来的风险元数据。
     * 只要工具目录认为某个工具需要审批，Run 就必须先进入 WAITING_HUMAN，
     * 这是一条商业化 Agent 的安全底线。
     */
    public boolean hasApprovalRequiredTool(AgentSessionRecord session) {
        return session.getToolBindings().stream()
                .anyMatch(toolExecutionAuditService::requiresApprovalBeforeExecution);
    }

    /**
     * 根据请求显式审批要求和工具风险，决定 Run 初始状态。
     *
     * @param explicitHumanApproval 用户或上游系统是否显式要求人工确认。
     * @param toolApprovalRequired 当前会话工具绑定是否天然要求审批。
     * @return 如果任一条件为 true，则进入 WAITING_HUMAN；否则进入 PLANNING。
     */
    public AgentRunState initialState(boolean explicitHumanApproval, boolean toolApprovalRequired) {
        return explicitHumanApproval || toolApprovalRequired
                ? AgentRunState.WAITING_HUMAN
                : AgentRunState.PLANNING;
    }

    /**
     * 生成 dry-run 阶段的下一步说明。
     *
     * <p>这些说明目前主要服务于前端展示、开发调试和学习理解。
     * 后续接入真实 LangGraph/OpenClaw 编排器后，可以把这里的文本型 nextActions 逐步升级为结构化计划节点，
     * 例如 MODEL_ROUTE、TOOL_PLAN、APPROVAL_GATE、TOOL_EXECUTION、RESULT_REVIEW。
     */
    public List<String> buildDryRunNextActions(AgentSessionRecord session,
                                               StartAgentRunRequest request,
                                               boolean explicitHumanApproval,
                                               boolean toolApprovalRequired) {
        List<String> actions = new ArrayList<>();
        actions.add("根据 workloadType=" + ModelWorkloadType.fromValue(request.workloadType()).name() + " 选择模型路由。");
        actions.add("读取会话工作空间 " + session.getWorkspaceKey() + "，确认租户/项目/工作空间隔离边界。");
        if (session.getToolBindings().isEmpty()) {
            actions.add("当前会话尚未绑定工具；真实执行前应绑定 datasource/data-quality/task-management 等工具。");
        } else {
            actions.add("根据 " + session.getToolBindings().size() + " 个工具绑定生成工具调用计划，并逐项做权限与风险校验。");
        }
        if (toolApprovalRequired) {
            actions.add("当前会话包含高风险或审批型工具，Agent Run 已进入 WAITING_HUMAN；必须先完成人工确认/审批，再允许工具进入真实执行。");
        } else if (explicitHumanApproval) {
            actions.add("本轮请求显式要求人工确认，应先创建审批/确认节点，再继续后续模型编排或工具执行。");
        }
        return actions;
    }

    /**
     * 根据审批来源生成运行状态说明。
     *
     * <p>把“为什么进入 WAITING_HUMAN”写进 message 很重要：
     * 审计人员、前端用户和后续排障人员不能只看到一个状态码，还需要知道状态来自用户主动要求，
     * 还是来自工具风险策略自动拦截。这个差异会影响审批文案、按钮权限和事故复盘结论。
     */
    public String buildRunCreatedMessage(boolean explicitHumanApproval, boolean toolApprovalRequired) {
        if (toolApprovalRequired) {
            return "Agent Run 已创建；当前会话绑定了高风险或审批型工具，运行已自动进入 WAITING_HUMAN，需先完成人工确认/审批。";
        }
        if (explicitHumanApproval) {
            return "Agent Run 已创建；本轮请求显式要求人工确认，运行已进入 WAITING_HUMAN，确认后再继续编排。";
        }
        return "Agent Run 已创建；当前为 dry-run 控制面状态，尚未交给真实模型或工具编排器执行。";
    }

    /**
     * 工具人工决策后回收 Run 级状态。
     *
     * <p>Run 和工具审计是两个不同粒度的状态机：
     * - 工具审计关心单个 toolCode 是否等待审批、已确认、已跳过或未来是否执行成功；
     * - Run 状态关心整次 Agent 编排是否还能继续向模型/工具阶段推进。
     *
     * <p>当前策略有意保守：
     * 1. 只要还有工具处于 WAITING_APPROVAL，Run 继续停在 WAITING_HUMAN；
     * 2. 只要出现 SKIPPED，说明用户拒绝了一个高风险工具，Run 进入 REJECTED 终态；
     * 3. 如果没有等待审批且没有拒绝，则说明审批门全部通过，Run 恢复到 PLANNING。
     */
    public void reconcileAfterToolDecision(AgentSessionRecord session, AgentRunRecord run) {
        if (run.getState() != AgentRunState.WAITING_HUMAN) {
            return;
        }
        List<AgentToolExecutionAuditView> audits = toolExecutionAuditService.listByRun(session.getSessionId(), run.getRunId());
        boolean hasWaitingApproval = audits.stream().anyMatch(item -> item.state().equals("WAITING_APPROVAL"));
        if (hasWaitingApproval) {
            return;
        }
        boolean hasRejectedTool = audits.stream().anyMatch(item -> item.state().equals("SKIPPED"));
        if (hasRejectedTool) {
            run.rejectAfterToolDecision("Agent Run 已因高风险工具计划被人工拒绝而终止；请调整目标或重新发起运行。");
            return;
        }
        run.resumePlanningAfterApproval(
                List.of(
                        "所有高风险工具计划已完成人工确认，Agent Run 已恢复到 PLANNING。",
                        "后续真实编排器应继续读取工具审计计划，并在执行前完成二次权限、项目范围和下游健康检查。"
                ),
                "所有高风险工具计划已确认，Agent Run 已恢复到 PLANNING，等待后续真实编排器继续推进。"
        );
    }
}
