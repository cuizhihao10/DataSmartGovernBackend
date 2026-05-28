/**
 * @Author : Cui
 * @Date: 2026/05/13 23:42
 * @Description DataSmart Govern Backend - AgentToolExecutionAuditView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Agent 工具执行审计视图。
 *
 * <p>该视图用于回答“某次 Agent Run 准备调用哪些工具、风险多高、是否需要审批、属于哪个租户/项目、由谁触发”。
 * 当前阶段还不执行真实工具，但先生成审计计划，可以避免未来接入工具后出现不可解释的黑盒调用。
 */
public record AgentToolExecutionAuditView(String auditId,
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
                                          String state,
                                          String traceId,
                                          String message,
                                          String approvalOperatorId,
                                          String approvalComment,
                                          LocalDateTime approvalTime,
                                          LocalDateTime executionStartTime,
                                          LocalDateTime executionFinishTime,
                                          String outputSummary,
                                          String errorCode,
                                          LocalDateTime createTime,
                                          LocalDateTime updateTime) {
}
