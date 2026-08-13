/**
 * @Author : Cui
 * @Date: 2026/05/13 22:48
 * @Description DataSmart Govern Backend - AgentRunRecord.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.session;

import com.czh.datasmart.govern.agent.model.AgentRunState;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
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
        this(runId, sessionId, state, workloadType, userInputPreview, dryRun, requireHumanApproval,
                nextActions, variables, createTime, createTime, null, message);
    }

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
                          LocalDateTime updateTime,
                          LocalDateTime finishTime,
                          String message) {
        this.runId = runId;
        this.sessionId = sessionId;
        this.state = state;
        this.workloadType = workloadType;
        this.userInputPreview = userInputPreview;
        this.dryRun = dryRun;
        this.requireHumanApproval = requireHumanApproval;
        this.nextActions = nextActions;
        this.variables = variables == null ? new LinkedHashMap<>() : new LinkedHashMap<>(variables);
        this.createTime = createTime;
        this.updateTime = updateTime == null ? createTime : updateTime;
        this.finishTime = finishTime;
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
        return Map.copyOf(variables);
    }

    /**
     * Persists a server-owned Run fact without allowing a later request or
     * model turn to replace it.
     */
    public void putVariableIfAbsent(String key, Object value) {
        if (key == null || key.isBlank() || value == null) {
            throw new IllegalArgumentException("Run variable key and value are required");
        }
        if (variables.containsKey(key)) {
            throw new IllegalStateException("Run variable is immutable once assigned: " + key);
        }
        variables.put(key, value);
        updateTime = LocalDateTime.now();
    }

    /**
     * 以一个服务器控制的变量作为守卫，原子写入一组不可覆盖的 Run 事实。
     *
     * <p>确认执行属于会产生真实副作用的一次性边界。调用方会把确认 claim 作为守卫，同时写入 claim 本身和
     * 可选的 Autopilot 授权；同一个 Run 后续再看到守卫变量时必须停止，而不能覆盖旧授权并重新执行工具。
     * 方法在领域对象上同步，保证 memory profile 中两个线程不会同时通过“尚未写入”的判断；生产 JDBC
     * 实现还会使用一条带 JSONB 条件的 UPDATE，在多实例之间提供同样的原子语义。</p>
     *
     * <p>输入 Map 的每个键都必须尚未存在。只要守卫已存在或任一待写键冲突，方法就返回 {@code false}，
     * 且不会写入部分字段。成功时会整体复制输入并推进更新时间。该方法不执行工具、不改变 Run 状态，
     * 也不解释变量内容的权限含义；这些职责仍属于调用它的确认服务。</p>
     *
     * @param guardVariable 用于判断本次一次性写入是否已经发生的服务器变量名
     * @param values 要一起写入的不可变低敏事实，必须包含 {@code guardVariable}
     * @return 首次完整写入返回 true；守卫或其他键已存在时返回 false
     * @throws IllegalArgumentException 当守卫、Map 或其中的键值为空时
     */
    public synchronized boolean putVariablesIfGuardAbsent(String guardVariable, Map<String, Object> values) {
        if (guardVariable == null || guardVariable.isBlank() || values == null || values.isEmpty()
                || !values.containsKey(guardVariable)) {
            throw new IllegalArgumentException("Guard variable and guarded Run variables are required");
        }
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                throw new IllegalArgumentException("Guarded Run variable keys and values are required");
            }
        }
        if (variables.containsKey(guardVariable)
                || values.keySet().stream().anyMatch(variables::containsKey)) {
            return false;
        }
        variables.putAll(new LinkedHashMap<>(values));
        updateTime = LocalDateTime.now();
        return true;
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

    /**
     * 所有工具节点成功后的 Run 终态。
     */
    public void completeAfterToolExecution(String message) {
        this.state = AgentRunState.SUCCEEDED;
        this.message = message;
        this.finishTime = LocalDateTime.now();
        this.updateTime = this.finishTime;
        this.nextActions = List.of(
                "Agent 控制面计划已完成，可前往数据同步任务详情查看真实 worker 执行进度、运行日志和结果。",
                "同步任务的最终成功或失败以 data-sync execution 状态为准。"
        );
    }

    /**
     * 任一必需工具节点失败后的 Run 终态。
     */
    public void failAfterToolExecution(String message) {
        this.state = AgentRunState.FAILED;
        this.message = message;
        this.finishTime = LocalDateTime.now();
        this.updateTime = this.finishTime;
        this.nextActions = List.of(
                "查看失败工具节点的具体错误和建议，修复数据源、映射或预检查问题。",
                "修复后重新生成 Agent 计划；失败 Run 不会自动重复写入业务系统。"
        );
    }

    /**
     * Applies a terminal lifecycle snapshot while leaving immutable Run variables untouched.
     *
     * <p>The memory store uses this method to mirror the JDBC narrow-update contract. The incoming snapshot may be
     * a different Java object when a test or local component reloaded the session between steps. Only lifecycle
     * fields are copied: confirmation claims, AUTOPILOT authorization and receipts remain in the current Run's
     * variable map and cannot be replaced by a stale aggregate.</p>
     *
     * <p>A different terminal state is rejected. This models the production SQL guard that prevents a late tool
     * success from replacing CANCELLED, REJECTED or FAILED. Reapplying the same terminal state is idempotent and
     * keeps the later update timestamp so local concurrent calls cannot move history time backwards.</p>
     *
     * @param snapshot terminal lifecycle produced by the governed execution path
     * @return {@code true} when this Run accepted or already represented the same terminal lifecycle
     */
    public synchronized boolean applyTerminalLifecycleSnapshot(AgentRunRecord snapshot) {
        if (snapshot == null
                || !runId.equals(snapshot.getRunId())
                || !sessionId.equals(snapshot.getSessionId())
                || snapshot.getState() == null
                || !snapshot.getState().isTerminal()) {
            return false;
        }
        if (state.isTerminal() && state != snapshot.getState()) {
            return false;
        }
        this.state = snapshot.getState();
        this.nextActions = snapshot.getNextActions() == null ? List.of() : List.copyOf(snapshot.getNextActions());
        this.message = snapshot.getMessage();
        this.finishTime = snapshot.getFinishTime();
        if (this.updateTime == null || (snapshot.getUpdateTime() != null
                && this.updateTime.isBefore(snapshot.getUpdateTime()))) {
            this.updateTime = snapshot.getUpdateTime();
        }
        return true;
    }

    /**
     * Applies the lifecycle result of one tool approval decision to the locally stored Run.
     *
     * <p>The current state must still be {@code WAITING_HUMAN}, unless this is an idempotent repeat of the same
     * target state. Accepted targets are {@code WAITING_HUMAN}, {@code PLANNING}, and {@code REJECTED}; other states
     * belong to model/tool execution and cannot be introduced through an approval callback. Variables are never
     * copied, so confirmation authorization facts remain immutable.</p>
     *
     * @param snapshot Run after the state coordinator reconciled current tool audits
     * @return {@code true} when the guarded approval lifecycle was applied or already present
     */
    public synchronized boolean applyToolDecisionLifecycleSnapshot(AgentRunRecord snapshot) {
        if (snapshot == null
                || !runId.equals(snapshot.getRunId())
                || !sessionId.equals(snapshot.getSessionId())
                || snapshot.getState() == null
                || !List.of(AgentRunState.WAITING_HUMAN, AgentRunState.PLANNING, AgentRunState.REJECTED)
                .contains(snapshot.getState())) {
            return false;
        }
        if (state != AgentRunState.WAITING_HUMAN && state != snapshot.getState()) {
            return false;
        }
        this.state = snapshot.getState();
        this.nextActions = snapshot.getNextActions() == null ? List.of() : List.copyOf(snapshot.getNextActions());
        this.message = snapshot.getMessage();
        this.finishTime = snapshot.getFinishTime();
        if (this.updateTime == null || (snapshot.getUpdateTime() != null
                && this.updateTime.isBefore(snapshot.getUpdateTime()))) {
            this.updateTime = snapshot.getUpdateTime();
        }
        return true;
    }
}
