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
 */
public record AgentSessionView(String sessionId,
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
                               LocalDateTime createTime,
                               LocalDateTime updateTime) {
}
