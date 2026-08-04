/**
 * @Author : Cui
 * @Date: 2026/05/13 22:43
 * @Description DataSmart Govern Backend - AgentSessionView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Agent 会话视图。
 *
 * <p>会话视图是管理后台和前端继续对话的核心对象。
 * 它同时暴露业务边界、工作空间、工具绑定和运行记录，让用户能理解“这个 Agent 正在什么范围内工作”。
 * 该视图不包含内部服务凭据或数据源密码等秘密。</p>
 *
 * @param sessionId 会话业务编号
 * @param agentId 实际执行的 Agent 主体编号
 * @param tenantId 会话所属租户
 * @param projectId 会话所属项目
 * @param workspaceId 兼容旧协议的可空工作空间编号，新隔离逻辑不依赖该字段
 * @param actorId 发起并拥有该会话的用户主体
 * @param channel 会话入口渠道
 * @param objective 会话初始目标
 * @param state 会话状态
 * @param workspace 运行时隔离键视图
 * @param toolBindings 本会话显式绑定的工具
 * @param runs 多轮运行记录
 * @param delegation Agent 的最小权限委托快照
 * @param messages 可继续追问的持久对话消息
 * @param pinned 是否在当前用户历史中置顶
 * @param archived 是否已归档
 * @param archivedAt 最近归档时间
 * @param lastMessageAt 最近有效消息时间
 * @param createTime 创建时间
 * @param updateTime 最近状态更新时间
 */
public record AgentSessionView(String sessionId,
                               String agentId,
                               Long tenantId,
                               Long projectId,
                               Long workspaceId,
                               String actorId,
                               String channel,
                               String objective,
                               String state,
                               AgentWorkspaceView workspace,
                               List<AgentToolBindingView> toolBindings,
                               List<AgentRunView> runs,
                               AgentDelegationView delegation,
                               List<AgentConversationMessageView> messages,
                               Boolean pinned,
                               Boolean archived,
                               LocalDateTime archivedAt,
                               LocalDateTime lastMessageAt,
                               LocalDateTime createTime,
                               LocalDateTime updateTime) {
}
