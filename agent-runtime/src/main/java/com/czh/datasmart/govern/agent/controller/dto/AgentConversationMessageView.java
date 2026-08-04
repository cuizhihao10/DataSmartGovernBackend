/**
 * @Author : Cui
 * @Date: 2026/08/04 00:00
 * @Description DataSmart Govern Backend - AgentConversationMessageView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.time.LocalDateTime;

/**
 * 可恢复到用户会话页面的低敏消息视图。
 *
 * <p>这里只承载用户主动输入和经过治理后允许公开展示的 Agent 回复，不包含模型隐藏推理、原始工具参数、
 * 凭证、SQL 正文或内部异常堆栈。前端通过 {@code runId} 可以解释一条消息属于哪次继续追问。</p>
 *
 * @param messageId 消息事实的稳定 ID，用于前端列表 key、审计定位和幂等恢复
 * @param runId 产生该消息的 Agent Run；旧数据或尚未绑定 Run 的系统消息可以为空
 * @param role 消息角色，当前业务值为 USER 或 AGENT
 * @param content 允许用户查看的消息正文，已经过控制面长度限制和低敏边界裁剪
 * @param createTime 消息进入 Java 控制面事实库的时间，用于恢复原始会话顺序
 */
public record AgentConversationMessageView(String messageId,
                                           String runId,
                                           String role,
                                           String content,
                                           LocalDateTime createTime) {
}
