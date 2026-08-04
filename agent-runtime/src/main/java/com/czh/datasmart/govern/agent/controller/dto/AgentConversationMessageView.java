/**
 * @Author : Cui
 * @Date: 2026/08/04 00:00
 * @Description DataSmart Govern Backend - AgentConversationMessageView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.time.LocalDateTime;

/** 可恢复到用户会话页面的消息。 */
public record AgentConversationMessageView(String messageId,
                                           String runId,
                                           String role,
                                           String content,
                                           LocalDateTime createTime) {
}
