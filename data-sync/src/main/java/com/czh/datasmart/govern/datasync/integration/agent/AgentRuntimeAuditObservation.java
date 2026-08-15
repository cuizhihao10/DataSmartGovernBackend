/**
 * @Author : Cui
 * @Date: 2026/08/15
 * @Description DataSmart Govern Backend - AgentRuntimeAuditObservation.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.integration.agent;

import java.time.LocalDateTime;

/**
 * Agent Runtime 工具审计的低敏本地快照。
 *
 * <p>只保留统一生命周期图所需的状态、风险、审批和时间字段；原始工具参数、治理 Map、模型输出和目标端点
 * 不进入这个 DTO，也不会从 data-sync 的图接口返回。</p>
 */
public record AgentRuntimeAuditObservation(
        boolean available,
        boolean found,
        String state,
        String toolCode,
        String riskLevel,
        Boolean requiresApproval,
        LocalDateTime executionStartTime,
        LocalDateTime executionFinishTime,
        LocalDateTime updateTime,
        String errorCode,
        String message,
        String sourceStatus,
        String auditId,
        String sessionId,
        String runId,
        Long tenantId,
        Long projectId) {

    /** 保留统一图既有构造方式；身份字段为空时只能用于展示，不能用于授权复核。 */
    public AgentRuntimeAuditObservation(boolean available,
                                        boolean found,
                                        String state,
                                        String toolCode,
                                        String riskLevel,
                                        Boolean requiresApproval,
                                        LocalDateTime executionStartTime,
                                        LocalDateTime executionFinishTime,
                                        LocalDateTime updateTime,
                                        String errorCode,
                                        String message,
                                        String sourceStatus) {
        this(available, found, state, toolCode, riskLevel, requiresApproval,
                executionStartTime, executionFinishTime, updateTime, errorCode, message, sourceStatus,
                null, null, null, null, null);
    }

    /** Agent Runtime 没有返回结果时的保守状态。 */
    public static AgentRuntimeAuditObservation unavailable(String sourceStatus) {
        return new AgentRuntimeAuditObservation(false, false, null, null, null, null,
                null, null, null, null, null, sourceStatus,
                null, null, null, null, null);
    }

    /** 查询成功但本次 run 中不存在目标 auditId 时的状态。 */
    public static AgentRuntimeAuditObservation missing() {
        return new AgentRuntimeAuditObservation(true, false, null, null, null, null,
                null, null, null, null, null, "AUDIT_NOT_FOUND",
                null, null, null, null, null);
    }
}
