/**
 * @Author : Cui
 * @Date: 2026/07/02 03:00
 * @Description DataSmart Govern Backend - AgentToolExecutionAuditViewMapper.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionAuditView;
import com.czh.datasmart.govern.agent.service.audit.AgentToolExecutionAuditRecord;

/**
 * 工具执行审计持久化记录到只读视图的映射器。
 *
 * <p>映射与状态推进分离后，{@link AgentToolExecutionAuditService} 可以专注审批、执行状态和事务 outbox
 * 边界。本类不修改记录、不发布事件，也不读取数据库；它只是完整复制允许通过 API 展示的审计字段。
 * 新增审计字段时应同时审查敏感性，不能把工具实参、凭据或原始模型输出直接加入视图。
 */
final class AgentToolExecutionAuditViewMapper {

    private AgentToolExecutionAuditViewMapper() {
    }

    static AgentToolExecutionAuditView toView(AgentToolExecutionAuditRecord record) {
        return new AgentToolExecutionAuditView(
                record.getAuditId(),
                record.getSessionId(),
                record.getRunId(),
                record.getBindingId(),
                record.getToolCode(),
                record.getToolType(),
                record.getTargetService(),
                record.getTargetEndpoint(),
                record.getTargetResourceId(),
                record.getTenantId(),
                record.getProjectId(),
                record.getWorkspaceId(),
                record.getActorId(),
                record.getRiskLevel(),
                record.getExecutionMode(),
                record.getRequiresApproval(),
                record.getReadOnly(),
                record.getIdempotent(),
                record.getAllowedActions(),
                record.getPlanReason(),
                record.getPlanArguments(),
                record.getGovernanceHints(),
                record.getParameterValidation(),
                record.getState().name(),
                record.getTraceId(),
                record.getMessage(),
                record.getApprovalOperatorId(),
                record.getApprovalComment(),
                record.getApprovalTime(),
                record.getExecutionStartTime(),
                record.getExecutionFinishTime(),
                record.getOutputSummary(),
                record.getErrorCode(),
                record.getCreateTime(),
                record.getUpdateTime()
        );
    }
}
