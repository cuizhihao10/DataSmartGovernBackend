/**
 * @Author : Cui
 * @Date: 2026/05/24 02:46
 * @Description DataSmart Govern Backend - AgentPlanIngestionIdempotencyRecord.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.plan;

import com.czh.datasmart.govern.agent.controller.dto.IngestedAgentPlanView;

import java.time.LocalDateTime;

/**
 * AgentPlan 接入幂等记录。
 *
 * <p>幂等记录用于解决一个真实生产问题：Python AI Runtime、智能网关或 Kafka Consumer 在网络超时、
 * 服务重启、ack 丢失或重试策略触发时，可能重复提交同一份 AgentPlan。
 * 如果 Java 控制面每次都创建新的 Run 和工具审计，就会出现重复审批、重复执行甚至重复写业务数据的风险。
 *
 * <p>当前记录仍保存在内存中，不是最终商业化实现。
 * 但字段已经按未来 MySQL/Redis 幂等表设计：
 * 1. `dedupeKey`：租户、项目、actor 与幂等键组合后的唯一键；
 * 2. `requestFingerprint`：同一个幂等键对应的请求摘要，用于识别“同 key 不同请求”的误用；
 * 3. `view`：首次成功接入后的响应快照，重复请求可直接回放；
 * 4. `createTime`：后续做 TTL 清理、排障和审计时需要。
 */
public record AgentPlanIngestionIdempotencyRecord(String dedupeKey,
                                                  String requestFingerprint,
                                                  IngestedAgentPlanView view,
                                                  LocalDateTime createTime) {
}
