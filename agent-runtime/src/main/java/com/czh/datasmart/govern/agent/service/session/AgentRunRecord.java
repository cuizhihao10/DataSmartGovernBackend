/**
 * @Author : Cui
 * @Date: 2026/05/13 22:48
 * @Description DataSmart Govern Backend - AgentRunRecord.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.session;

import com.czh.datasmart.govern.agent.model.AgentRunState;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 内部 Agent 运行记录。
 *
 * <p>该记录描述一次 Agent 编排尝试。
 * 第一版不会真正进入模型、工具、审批节点，但仍然先固定 runId、state、workloadType、variables、nextActions。
 * 这样后续接入真实运行时后，可以在同一对象上追加模型路由、工具调用事件、token 统计、错误原因等字段。
 */
public class AgentRunRecord {

    /**
     * 运行 ID。
     * 该 ID 应出现在日志、审计、下游工具调用和前端进度推送中，形成可追踪链路。
     */
    private final String runId;

    /**
     * 所属会话 ID。
     */
    private final String sessionId;

    /**
     * 当前运行状态。
     */
    private AgentRunState state;

    /**
     * 模型工作负载类型。
     */
    private final String workloadType;

    /**
     * 用户输入预览。
     * 真实 prompt 可能很长且包含敏感信息，当前只保留摘要，后续完整输入应按合规策略写入加密审计或对象存储。
     */
    private final String userInputPreview;

    /**
     * 是否 dry-run。
     * 当前 Agent 编排器尚未接入真实模型和工具，所以第一版运行都显式标记为 dry-run。
     */
    private final Boolean dryRun;

    /**
     * 是否要求人工确认。
     */
    private final Boolean requireHumanApproval;

    /**
     * 下一步建议。
     * 当前用于指导研发和前端理解后续应接入的能力，未来可以替换为真实编排计划节点。
     */
    private List<String> nextActions;

    /**
     * 运行变量。
     */
    private final Map<String, Object> variables;

    private final LocalDateTime createTime;
    private LocalDateTime updateTime;
    private LocalDateTime finishTime;

    /**
     * 当前状态说明。
     */
    private String message;

    public AgentRunRecord(String runId,
                          String sessionId,
                          AgentRunState state,
                          String workloadType,
                          String userInputPreview,
                          Boolean dryRun,
                          Boolean requireHumanApproval,
                          List<String> nextActions,
                          Map<String, Object> variables,
                          LocalDateTime createTime,
                          String message) {
        this.runId = runId;
        this.sessionId = sessionId;
        this.state = state;
        this.workloadType = workloadType;
        this.userInputPreview = userInputPreview;
        this.dryRun = dryRun;
        this.requireHumanApproval = requireHumanApproval;
        this.nextActions = nextActions;
        this.variables = variables;
        this.createTime = createTime;
        this.updateTime = createTime;
        this.message = message;
    }

    public String getRunId() {
        return runId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public AgentRunState getState() {
        return state;
    }

    public String getWorkloadType() {
        return workloadType;
    }

    public String getUserInputPreview() {
        return userInputPreview;
    }

    public Boolean getDryRun() {
        return dryRun;
    }

    public Boolean getRequireHumanApproval() {
        return requireHumanApproval;
    }

    public List<String> getNextActions() {
        return nextActions;
    }

    public Map<String, Object> getVariables() {
        return variables;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public LocalDateTime getFinishTime() {
        return finishTime;
    }

    public String getMessage() {
        return message;
    }

    /**
     * 所有高风险工具都已完成人工确认后，恢复到规划阶段。
     *
     * <p>该方法只负责更新 Run 的内存状态，不直接执行工具。
     * 真实产品里，恢复到 PLANNING 后应由编排器继续读取工具审计计划，并决定下一步进入模型规划、工具调用或结果复核。
     *
     * @param nextActions 恢复规划后给前端和编排器的下一步提示。
     * @param message 状态说明，用于审计和前端展示。
     */
    public void resumePlanningAfterApproval(List<String> nextActions, String message) {
        this.state = AgentRunState.PLANNING;
        this.nextActions = nextActions;
        this.message = message;
        this.updateTime = LocalDateTime.now();
    }

    /**
     * 因高风险工具被人工拒绝而终止运行。
     *
     * <p>这里使用 REJECTED，而不是 FAILED：
     * 用户拒绝高风险工具是一个正常的安全治理结果，不应被报表统计为系统错误。
     * 进入终态后，会话可以发起新的 Run，让用户调整目标、解绑工具或改用低风险方案。
     *
     * @param message 拒绝说明。
     */
    public void rejectAfterToolDecision(String message) {
        this.state = AgentRunState.REJECTED;
        this.message = message;
        this.finishTime = LocalDateTime.now();
        this.updateTime = this.finishTime;
        this.nextActions = List.of(
                "当前 Agent Run 已因高风险工具被拒绝而终止。",
                "如需继续，请调整治理目标、移除被拒绝工具或在同一会话中创建新的 Agent Run。"
        );
    }

    /**
     * 取消运行。
     *
     * <p>取消操作只允许从非终态进入 CANCELLED。这样可以防止已经成功或失败的运行被后续误改为取消，
     * 保证审计事实不可被随意覆盖。
     */
    public void cancel(String message) {
        this.state = AgentRunState.CANCELLED;
        this.message = message;
        this.finishTime = LocalDateTime.now();
        this.updateTime = this.finishTime;
        this.nextActions = List.of("如需继续，请在同一会话中创建新的 Agent Run。");
    }
}
