/**
 * @Author : Cui
 * @Date: 2026/05/13 23:45
 * @Description DataSmart Govern Backend - AgentToolExecutionAuditRecord.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.audit;

import com.czh.datasmart.govern.agent.model.AgentToolExecutionState;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 工具执行审计内部记录。
 *
 * <p>当前记录保存在内存中，作用是先固定审计事实结构。
 * 后续迁移到数据库或事件流时，应保持这些关键字段：sessionId、runId、bindingId、toolCode、风险、审批要求、租户/项目和 traceId。
 */
public class AgentToolExecutionAuditRecord {

    private final String auditId;
    private final String sessionId;
    private final String runId;
    private final String bindingId;
    private final String toolCode;
    private final String toolType;
    private final String targetService;
    private final String targetEndpoint;
    private final Long targetResourceId;
    private final Long tenantId;
    private final Long projectId;
    private final Long workspaceId;
    private final String actorId;
    private final String riskLevel;
    private final String executionMode;
    private final Boolean requiresApproval;
    private final Boolean readOnly;
    private final Boolean idempotent;
    private final List<String> allowedActions;
    /**
     * Agent 规划阶段给出的工具调用原因。
     *
     * <p>传统后端审计通常只记录“调用了哪个接口”，但智能体产品还必须记录“为什么模型/编排器认为应该调用它”。
     * 该字段来自 Python AgentPlan 的 toolPlan.reason，后续可用于人工审批、事故复盘、模型评估和提示词优化。
     */
    private final String planReason;

    /**
     * Python AgentPlan 规划出的工具参数快照。
     *
     * <p>这里保存的是进入 Java 控制面时的参数快照，不等同于最终下游服务收到的参数。
     * 真正执行前，Java 工具适配器仍需要根据租户、项目、权限和目标资源做二次校验与参数清洗。
     */
    private final Map<String, Object> planArguments;

    /**
     * 工具治理提示。
     *
     * <p>该字段承载 Python Runtime 从 MCP-style descriptor、Skill、记忆检索或模型网关推导出的治理信息，
     * 例如 tenantScoped、projectScoped、sensitiveFields、memoryWritePolicy、cachePolicy。
     * Java 控制面不会盲目信任这些提示，但会保留它们作为审批说明和后续策略升级依据。
     */
    private final Map<String, Object> governanceHints;

    /**
     * 参数校验结果快照。
     *
     * <p>当前 Python Runtime 已经具备参数缺失/上下文补齐/敏感字段标记等规划能力。
     * Java 侧先把这些结果写入审计，后续再逐步升级为强类型参数校验表或审批单字段。
     */
    private final Map<String, Object> parameterValidation;
    private AgentToolExecutionState state;
    private final String traceId;
    private String message;
    private String approvalOperatorId;
    private String approvalComment;
    private LocalDateTime approvalTime;
    private LocalDateTime executionStartTime;
    private LocalDateTime executionFinishTime;
    private String outputSummary;
    private String errorCode;
    private final LocalDateTime createTime;
    private LocalDateTime updateTime;

    public AgentToolExecutionAuditRecord(String auditId,
                                         String sessionId,
                                         String runId,
                                         String bindingId,
                                         String toolCode,
                                         String toolType,
                                         String targetService,
                                         String targetEndpoint,
                                         Long targetResourceId,
                                         Long tenantId,
                                         Long projectId,
                                         Long workspaceId,
                                         String actorId,
                                         String riskLevel,
                                         String executionMode,
                                         Boolean requiresApproval,
                                         Boolean readOnly,
                                         Boolean idempotent,
                                         List<String> allowedActions,
                                         AgentToolExecutionState state,
                                         String traceId,
                                         String message,
                                         LocalDateTime createTime) {
        this(auditId,
                sessionId,
                runId,
                bindingId,
                toolCode,
                toolType,
                targetService,
                targetEndpoint,
                targetResourceId,
                tenantId,
                projectId,
                workspaceId,
                actorId,
                riskLevel,
                executionMode,
                requiresApproval,
                readOnly,
                idempotent,
                allowedActions,
                null,
                Map.of(),
                Map.of(),
                Map.of(),
                state,
                traceId,
                message,
                createTime);
    }

    public AgentToolExecutionAuditRecord(String auditId,
                                         String sessionId,
                                         String runId,
                                         String bindingId,
                                         String toolCode,
                                         String toolType,
                                         String targetService,
                                         String targetEndpoint,
                                         Long targetResourceId,
                                         Long tenantId,
                                         Long projectId,
                                         Long workspaceId,
                                         String actorId,
                                         String riskLevel,
                                         String executionMode,
                                         Boolean requiresApproval,
                                         Boolean readOnly,
                                         Boolean idempotent,
                                         List<String> allowedActions,
                                         String planReason,
                                         Map<String, Object> planArguments,
                                         Map<String, Object> governanceHints,
                                         Map<String, Object> parameterValidation,
                                         AgentToolExecutionState state,
                                         String traceId,
                                         String message,
                                         LocalDateTime createTime) {
        this.auditId = auditId;
        this.sessionId = sessionId;
        this.runId = runId;
        this.bindingId = bindingId;
        this.toolCode = toolCode;
        this.toolType = toolType;
        this.targetService = targetService;
        this.targetEndpoint = targetEndpoint;
        this.targetResourceId = targetResourceId;
        this.tenantId = tenantId;
        this.projectId = projectId;
        this.workspaceId = workspaceId;
        this.actorId = actorId;
        this.riskLevel = riskLevel;
        this.executionMode = executionMode;
        this.requiresApproval = requiresApproval;
        this.readOnly = readOnly;
        this.idempotent = idempotent;
        this.allowedActions = allowedActions;
        this.planReason = planReason;
        this.planArguments = immutableJsonMap(planArguments);
        this.governanceHints = immutableJsonMap(governanceHints);
        this.parameterValidation = immutableJsonMap(parameterValidation);
        this.state = state;
        this.traceId = traceId;
        this.message = message;
        this.createTime = createTime;
        this.updateTime = createTime;
    }

    private static Map<String, Object> immutableJsonMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    /**
     * 从持久化介质恢复可变状态字段。
     *
     * <p>正常业务流转应该通过 {@link #approve(String, String)}、{@link #reject(String, String)}、
     * {@link #startExecution(String)}、{@link #succeed(String, String)} 和 {@link #fail(String, String)} 推进状态，
     * 因为这些方法会同时维护状态、时间和说明。数据库仓储从 ResultSet 还原对象时则不应该重新触发这些业务动作，
     * 否则会把历史审批时间改成当前时间，或把历史输出摘要覆盖掉。</p>
     *
     * <p>因此这里提供一个包内恢复方法，只允许同包的持久化适配器调用。它不是给 Controller 或 Service 直接修改状态用的，
     * 也是为了避免外部绕过审计状态机随意篡改工具执行事实。</p>
     */
    void restoreMutableFields(String approvalOperatorId,
                              String approvalComment,
                              LocalDateTime approvalTime,
                              LocalDateTime executionStartTime,
                              LocalDateTime executionFinishTime,
                              String outputSummary,
                              String errorCode,
                              LocalDateTime updateTime) {
        this.approvalOperatorId = approvalOperatorId;
        this.approvalComment = approvalComment;
        this.approvalTime = approvalTime;
        this.executionStartTime = executionStartTime;
        this.executionFinishTime = executionFinishTime;
        this.outputSummary = outputSummary;
        this.errorCode = errorCode;
        this.updateTime = updateTime == null ? this.updateTime : updateTime;
    }

    public String getAuditId() {
        return auditId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getRunId() {
        return runId;
    }

    public String getBindingId() {
        return bindingId;
    }

    public String getToolCode() {
        return toolCode;
    }

    public String getToolType() {
        return toolType;
    }

    public String getTargetService() {
        return targetService;
    }

    public String getTargetEndpoint() {
        return targetEndpoint;
    }

    public Long getTargetResourceId() {
        return targetResourceId;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public Long getProjectId() {
        return projectId;
    }

    public Long getWorkspaceId() {
        return workspaceId;
    }

    public String getActorId() {
        return actorId;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public String getExecutionMode() {
        return executionMode;
    }

    public Boolean getRequiresApproval() {
        return requiresApproval;
    }

    public Boolean getReadOnly() {
        return readOnly;
    }

    public Boolean getIdempotent() {
        return idempotent;
    }

    public List<String> getAllowedActions() {
        return allowedActions;
    }

    public String getPlanReason() {
        return planReason;
    }

    public Map<String, Object> getPlanArguments() {
        return planArguments;
    }

    public Map<String, Object> getGovernanceHints() {
        return governanceHints;
    }

    public Map<String, Object> getParameterValidation() {
        return parameterValidation;
    }

    public AgentToolExecutionState getState() {
        return state;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getMessage() {
        return message;
    }

    public String getApprovalOperatorId() {
        return approvalOperatorId;
    }

    public String getApprovalComment() {
        return approvalComment;
    }

    public LocalDateTime getApprovalTime() {
        return approvalTime;
    }

    public LocalDateTime getExecutionStartTime() {
        return executionStartTime;
    }

    public LocalDateTime getExecutionFinishTime() {
        return executionFinishTime;
    }

    public String getOutputSummary() {
        return outputSummary;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    /**
     * 人工确认高风险工具计划。
     *
     * <p>确认并不代表立即执行工具，而是把工具从 WAITING_APPROVAL 放回 PLANNED。
     * 这样真实工具适配器仍然可以在执行前继续做二次权限校验、项目范围校验、幂等校验和下游健康检查。
     *
     * @param operatorId 确认人 ID，用于审计追踪。
     * @param comment 确认说明，用于后续复盘用户为什么允许该工具继续。
     */
    public void approve(String operatorId, String comment) {
        this.state = AgentToolExecutionState.PLANNED;
        this.approvalOperatorId = operatorId;
        this.approvalComment = comment;
        this.approvalTime = LocalDateTime.now();
        this.updateTime = this.approvalTime;
        this.message = "高风险工具计划已由人工确认，后续真实执行前仍需进行权限、项目范围和下游服务校验。";
    }

    /**
     * 人工拒绝高风险工具计划。
     *
     * <p>拒绝后进入 SKIPPED，而不是 FAILED。
     * 原因是工具并没有真正执行失败，而是被人类在执行前主动跳过；这对报表统计和事故分析非常重要。
     *
     * @param operatorId 拒绝人 ID。
     * @param comment 拒绝原因。
     */
    public void reject(String operatorId, String comment) {
        this.state = AgentToolExecutionState.SKIPPED;
        this.approvalOperatorId = operatorId;
        this.approvalComment = comment;
        this.approvalTime = LocalDateTime.now();
        this.updateTime = this.approvalTime;
        this.message = "高风险工具计划已被人工拒绝，后续编排器应跳过该工具并向用户解释影响范围。";
    }

    /**
     * 标记工具开始执行。
     *
     * <p>只有进入 EXECUTING 后，后续才允许写入 SUCCEEDED 或 FAILED。
     * 这能让审计链路清晰表达：工具不是瞬间从计划态跳到结果态，而是经历了真实执行窗口。
     */
    public void startExecution(String message) {
        this.state = AgentToolExecutionState.EXECUTING;
        this.executionStartTime = LocalDateTime.now();
        this.updateTime = this.executionStartTime;
        this.message = message;
        this.errorCode = null;
    }

    /**
     * 刷新执行中的状态说明，但不重置 executionStartTime。
     *
     * <p>该方法主要服务异步任务的 DEFERRED/RETRYING 类进度更新。
     * 对 Agent 审计来说，任务仍然处于 EXECUTING，只是 worker 因下游限流、临时不可用或状态回写失败而回队列等待重试。
     * 如果复用 startExecution(...)，会把真正开始执行的时间覆盖成最近一次退避时间，导致后续 SLA、耗时统计和事故复盘失真。</p>
     *
     * @param message 最新执行说明，应面向前端和审计人员可读。
     */
    public void updateExecutionMessage(String message) {
        this.message = message;
        this.updateTime = LocalDateTime.now();
    }

    /**
     * 标记工具执行成功。
     *
     * @param message 成功说明。
     * @param outputSummary 输出摘要。不要写入大量明细或敏感数据，真实结果应进入对象存储或业务表。
     */
    public void succeed(String message, String outputSummary) {
        this.state = AgentToolExecutionState.SUCCEEDED;
        this.executionFinishTime = LocalDateTime.now();
        this.updateTime = this.executionFinishTime;
        this.message = message;
        this.outputSummary = outputSummary;
        this.errorCode = null;
    }

    /**
     * 标记工具执行失败。
     *
     * <p>失败不一定是系统 Bug，也可能是下游服务不可用、权限二次校验失败、目标资源不存在或超时。
     * 因此这里保留 errorCode，后续可用于告警聚合和失败分类报表。
     */
    public void fail(String errorCode, String message) {
        this.state = AgentToolExecutionState.FAILED;
        this.executionFinishTime = LocalDateTime.now();
        this.updateTime = this.executionFinishTime;
        this.errorCode = errorCode;
        this.message = message;
    }
}
