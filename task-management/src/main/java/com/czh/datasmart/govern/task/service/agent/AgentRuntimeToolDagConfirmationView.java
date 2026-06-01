/**
 * @Author : Cui
 * @Date: 2026/06/01 23:35
 * @Description DataSmart Govern Backend - AgentRuntimeToolDagConfirmationView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

import java.time.Instant;
import java.util.List;

/**
 * task-management 侧读取到的 Agent Runtime DAG selected-node 确认快照。
 *
 * <p>这个 record 是跨服务 DTO 的本地投影，而不是直接复用 agent-runtime 模块里的 Java 类型。
 * 微服务之间应该通过 HTTP/JSON 契约交互，避免 task-management 在编译期依赖 agent-runtime 的内部包。
 * 这样以后 agent-runtime 可以把确认记录存到数据库、ClickHouse 或审计存储，也可以扩展内部实体字段，
 * 只要 HTTP 返回契约保持兼容，worker 执行前复核就不需要跟着重构。</p>
 *
 * <p>安全边界说明：
 * 该对象只承载“确认事实”和“低敏审计证据”，例如确认 ID、选中审计 ID、命令 ID、策略版本摘要。
 * 它不能承载工具参数、SQL、prompt、样本数据、文件内容或原始 payload，否则 task-management 会变成第二份
 * Agent 工具参数存储，破坏 payloadReference 的最小暴露原则。</p>
 *
 * @param confirmationId DAG 选中节点确认 ID，用于把用户确认、outbox 命令和 worker 执行串成同一条证据链
 * @param sessionId Agent 会话 ID，worker 会要求它和当前任务 payload 中的 sessionId 一致
 * @param runId Agent Run ID，worker 会要求它和当前任务 payload 中的 runId 一致
 * @param selectionFingerprint 确认时服务端 dry-run 得到的选中节点指纹，用于未来检测“确认后计划被改写”的风险
 * @param selectedNodeIds 被确认的 DAG 节点 ID，仅用于审计展示，不参与当前 worker 的最小一致性判断
 * @param selectedAuditIds 被确认的工具审计 ID 集合，worker 必须确认当前 auditId 包含在其中
 * @param policyVersions 确认时涉及的权限/路由/治理策略版本摘要，worker 会要求任务携带的版本被该集合覆盖
 * @param delegationEvidence 服务账号代表用户执行的委托证据摘要，必须是低敏文本
 * @param outboxIds 确认动作产生或关联的 command outbox ID，用于后续排障和补偿台跳转
 * @param commandIds 确认动作产生或关联的异步 commandId，worker 必须确认当前 commandId 包含在其中
 * @param tenantId 确认记录所属租户，用于审计、排障和未来跨租户隔离复核
 * @param projectId 确认记录所属项目，用于未来项目级配额、项目负责人审批和审计筛选
 * @param workspaceId 确认记录所属工作空间，用于未来工作空间级 Agent 编排和资源隔离
 * @param actorId 发起确认的用户或服务主体 ID，用于追踪是谁把节点放入可执行队列
 * @param traceId 确认动作的链路 ID，用于串联 gateway、permission-admin、agent-runtime 与 task-management 日志
 * @param confirmed 当前记录是否代表已确认动作，当前阶段应为 true，未来可支持撤销/拒绝状态
 * @param status 确认状态字符串；task-management 只识别 CONFIRMED，避免编译期依赖 agent-runtime 枚举
 * @param expiresAt 确认证据过期时间；当前仅记录进诊断，未来可升级为执行前强制重新确认
 * @param createdAt 确认记录创建时间
 * @param updatedAt 确认记录最后更新时间
 */
public record AgentRuntimeToolDagConfirmationView(
        String confirmationId,
        String sessionId,
        String runId,
        String selectionFingerprint,
        List<String> selectedNodeIds,
        List<String> selectedAuditIds,
        List<String> policyVersions,
        List<String> delegationEvidence,
        List<String> outboxIds,
        List<String> commandIds,
        Long tenantId,
        Long projectId,
        Long workspaceId,
        String actorId,
        String traceId,
        Boolean confirmed,
        String status,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt
) {

    public AgentRuntimeToolDagConfirmationView {
        selectedNodeIds = selectedNodeIds == null ? List.of() : List.copyOf(selectedNodeIds);
        selectedAuditIds = selectedAuditIds == null ? List.of() : List.copyOf(selectedAuditIds);
        policyVersions = policyVersions == null ? List.of() : List.copyOf(policyVersions);
        delegationEvidence = delegationEvidence == null ? List.of() : List.copyOf(delegationEvidence);
        outboxIds = outboxIds == null ? List.of() : List.copyOf(outboxIds);
        commandIds = commandIds == null ? List.of() : List.copyOf(commandIds);
    }
}
