/**
 * @Author : Cui
 * @Date: 2026/06/01 22:18
 * @Description DataSmart Govern Backend - AgentRunToolDagConfirmationView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import com.czh.datasmart.govern.agent.service.execution.confirmation.AgentRunToolDagConfirmationStatus;

import java.time.Instant;
import java.util.List;

/**
 * DAG selected-node 确认记录的审计展示视图。
 *
 * <p>该 DTO 是对内部 {@code AgentRunToolDagConfirmationRecord} 的安全投影。
 * 它面向前端审计台、网关诊断、运营排障和未来管理员补偿台，回答“谁在什么时候确认了哪批 DAG 节点进入异步 outbox”。
 * 这里刻意不返回工具参数、SQL、prompt、文件样本、原始 payload 或异常堆栈，因为确认记录的职责是保存治理证据，
 * 不是成为第二份工具执行明细表。</p>
 *
 * @param confirmationId 稳定确认 ID，由 sessionId、runId、selectionFingerprint 和 selectedAuditIds 派生，用于幂等和审计串联
 * @param sessionId Agent 会话 ID，用于把确认事实限定到一次会话上下文
 * @param runId Agent Run ID，用于把确认事实限定到一次模型规划或执行尝试
 * @param selectionFingerprint dry-run 预案指纹，用于证明确认时看到的节点集合与服务端重新校验的一致
 * @param selectedNodeIds 被确认的 DAG 节点 ID 列表，只描述节点身份，不暴露节点参数
 * @param selectedAuditIds 被确认的工具审计 ID 列表，方便跳转工具执行审计记录
 * @param policyVersions permission-admin 在预检或入箱链路返回的策略版本快照
 * @param delegationEvidence 服务账号代表用户执行时的委托证据摘要，禁止包含敏感业务 payload
 * @param outboxIds 本次确认关联的 command outbox 记录 ID 列表，用于后续排查投递状态
 * @param commandIds 本次确认关联的异步命令 ID 列表，用于和 task-management 或 worker 日志串联
 * @param tenantId 租户 ID，用于多租户审计与权限收口
 * @param projectId 项目 ID，用于项目负责人和项目审计的数据范围过滤
 * @param workspaceId 工作空间 ID，用于未来工作空间级审计台或配额统计
 * @param actorId 发起确认的主体 ID，SELF 范围会基于该字段收口
 * @param traceId 确认请求链路 ID，用于网关、权限中心、agent-runtime 日志串联
 * @param confirmed 当前记录是否代表确认动作；预留给后续撤销、拒绝、过期等扩展
 * @param status 确认状态，当前主要为 CONFIRMED，后续可扩展 EXPIRED/REVOKED/REPLAYED 等治理状态
 * @param expiresAt 确认证据过期时间；过期后不代表记录删除，而是后续执行前应重新确认
 * @param createdAt 确认事实创建时间
 * @param updatedAt 确认事实最后更新时间
 */
public record AgentRunToolDagConfirmationView(
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
        AgentRunToolDagConfirmationStatus status,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt
) {
}
