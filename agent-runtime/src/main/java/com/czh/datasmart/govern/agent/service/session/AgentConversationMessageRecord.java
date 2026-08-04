/**
 * @Author : Cui
 * @Date: 2026/08/04 00:00
 * @Description DataSmart Govern Backend - AgentConversationMessageRecord.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.session;

import java.time.LocalDateTime;

/** 用户可见的会话消息事实。仅保存用户输入和经过治理的 Agent 公共回复，不保存隐藏推理。 */
public record AgentConversationMessageRecord(String messageId,
                                             String runId,
                                             String role,
                                             String content,
                                             LocalDateTime createTime) {
}
