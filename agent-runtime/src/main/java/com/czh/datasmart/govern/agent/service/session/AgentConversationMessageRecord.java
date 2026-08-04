/**
 * @Author : Cui
 * @Date: 2026/08/04 00:00
 * @Description DataSmart Govern Backend - AgentConversationMessageRecord.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.session;

import java.time.LocalDateTime;

/**
 * 用户可见的会话消息事实。
 *
 * <p>该 record 是持久化聚合的一部分，只保存用户输入和经过治理的 Agent 公共回复，不保存隐藏推理。
 * 将“用户可见消息”与 runtime event、模型原始响应和工具审计分开，可以避免历史会话接口意外泄露内部链路信息。</p>
 *
 * @param messageId 全局稳定消息 ID
 * @param runId 所属 Run ID，用于一轮轮恢复对话；允许兼容旧消息为空
 * @param role USER 或 AGENT，决定前端展示方向
 * @param content 经过长度限制的用户可见正文
 * @param createTime 消息事实创建时间，也是同一 session 内的主要排序依据
 */
public record AgentConversationMessageRecord(String messageId,
                                             String runId,
                                             String role,
                                             String content,
                                             LocalDateTime createTime) {
}
