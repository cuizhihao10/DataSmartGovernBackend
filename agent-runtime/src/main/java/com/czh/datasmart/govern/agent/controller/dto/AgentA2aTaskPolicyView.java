/**
 * @Author : Cui
 * @Date: 2026/06/06 12:40
 * @Description DataSmart Govern Backend - AgentA2aTaskPolicyView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;
import java.util.Map;

/**
 * A2A Task 生命周期治理策略视图。
 *
 * <p>真实 task endpoint 不只是“收一个请求并返回状态”。它需要同时回答：谁能提交任务、能否取消、取消是否安全、
 * 何时超时、如何防重复提交、审批如何回到任务状态、streaming/push 如何投递、失败如何重试以及哪些内容不能进入事件。
 * 本 DTO 用结构化字段把这些策略一次性固定下来，后续实现 task API 时可以逐项落地。</p>
 *
 * @param cancellationPolicy 取消策略。重点说明副作用前可取消、副作用后需 worker 确认或补偿
 * @param idempotencyPolicy 幂等策略。真实提交、取消、继续输入都需要幂等键或消息 ID
 * @param authorizationPolicy 认证授权策略。A2A auth-required 应映射到 DataSmart 权限/审批缺口，而不是明文交换凭证
 * @param timeoutPolicy 超时策略，按 submitted、input-required、auth-required、worker heartbeat 等窗口区分
 * @param retryPolicy 重试策略，说明哪些阶段可自动重试、哪些必须人工介入
 * @param streamingPolicy streaming 策略，当前尚未启用，但要定义事件粒度和敏感载荷边界
 * @param pushNotificationPolicy push notification 策略，当前尚未启用，但要定义 webhook 安全、签名和退避
 * @param persistencePolicy 持久化策略，说明 task 状态、事件、outbox、worker receipt 的关系
 * @param privacyPolicy 隐私与脱敏策略，禁止 prompt、工具参数、资源正文、模型输出和密钥扩散
 * @param performanceTargets 初始性能目标草案，用于后续压测和容量规划
 */
public record AgentA2aTaskPolicyView(
        List<String> cancellationPolicy,
        List<String> idempotencyPolicy,
        List<String> authorizationPolicy,
        Map<String, Object> timeoutPolicy,
        List<String> retryPolicy,
        List<String> streamingPolicy,
        List<String> pushNotificationPolicy,
        List<String> persistencePolicy,
        List<String> privacyPolicy,
        Map<String, Object> performanceTargets
) {
}
