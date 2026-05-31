/**
 * @Author : Cui
 * @Date: 2026/05/31 23:58
 * @Description DataSmart Govern Backend - AgentAsyncToolTaskStatusCallbackRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.Map;

/**
 * Agent 异步工具任务状态回调请求。
 *
 * <p>这个 DTO 是 task-management worker 回写 agent-runtime 的内部控制面契约。
 * 它不是给浏览器或普通业务用户使用的公开 API，而是用于把“长耗时工具在任务中心的真实执行进度”
 * 映射回 Agent 工具审计状态机。</p>
 *
 * <p>为什么需要独立回调契约：</p>
 * <p>1. task-management 负责认领、租约、重试、defer 和任务生命周期；</p>
 * <p>2. agent-runtime 负责 Agent 会话、工具审计、runtime event 和 Python 二轮推理可见性；</p>
 * <p>3. 两者不能直接共享数据库或互相修改内部表，否则模块边界会被打穿，后续微服务拆分、租户隔离和审计追责都会变困难。</p>
 *
 * @param commandId Agent Runtime 下发到 task-management 的异步命令 ID，用于串联 outbox、Kafka、Inbox、task 和回调。
 * @param taskId task-management 中创建出的任务 ID，用于前端或运维人员从 Agent 审计反查任务中心。
 * @param taskRunId task-management 当前执行 run ID，用于定位某一次认领/执行尝试。
 * @param executorId 触发回调的 worker 身份，生产环境可结合服务账号、mTLS 或签名校验。
 * @param status worker 侧状态，当前支持 RUNNING、SUCCEEDED、FAILED、DEFERRED。
 * @param message 面向人类的状态说明，进入审计 message，便于学习、排障和前端展示。
 * @param errorCode 失败分类码，只有 FAILED 通常需要；DEFERRED 更偏容量/临时不可用，不一定是业务失败。
 * @param outputSummary 允许进入 Agent 审计和模型反馈链路的安全摘要，不应包含样本数据、密钥或大结果。
 * @param output 结构化输出摘要，首版只作为回调诊断字段保留，不直接持久化完整工具结果。
 * @param idempotencyKey 回调幂等键，后续接入回调去重表时可用于防止重复推进同一状态。
 */
public record AgentAsyncToolTaskStatusCallbackRequest(
        String commandId,
        Long taskId,
        Long taskRunId,
        String executorId,
        String status,
        String message,
        String errorCode,
        String outputSummary,
        Map<String, Object> output,
        String idempotencyKey) {
}
