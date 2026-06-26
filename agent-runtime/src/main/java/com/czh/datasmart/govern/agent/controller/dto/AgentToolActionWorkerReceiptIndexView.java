/**
 * @Author : Cui
 * @Date: 2026/06/18 00:00
 * @Description DataSmart Govern Backend - AgentToolActionWorkerReceiptIndexView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.time.Instant;

/**
 * worker receipt 低敏索引查询视图。
 *
 * <p>该 DTO 面向管理台、审计台和智能网关排障面，回答“某个 command 是否已经被 dry-run/worker
 * 看见，以及执行前检查的低敏结果是什么”。它不是 receipt 原文，也不是 task-management 的完整任务日志。
 * 因此这里故意只保留机器可读状态、租户/项目/run/session 边界和 replay 游标。</p>
 *
 * <p>安全边界非常关键：响应不包含 eventIdentityKey 原文，只返回短指纹；不包含 receipt message、
 * payload body、工具参数、SQL、prompt、样本数据、模型输出、凭证、token 或内部 endpoint。
 * 如果未来真实 worker side-effect receipt 上线，也必须继续遵守这个白名单输出模型。</p>
 */
public record AgentToolActionWorkerReceiptIndexView(
        /** 是否存在底层 eventIdentityKey。该布尔值只用于判断幂等事实是否具备来源，不泄露来源明文。 */
        Boolean eventIdentityKeyPresent,

        /** eventIdentityKey 的 SHA-256 短指纹，用于排障关联同一条 receipt，但不能反推出原始 key。 */
        String eventIdentityKeyFingerprint,

        /** Java command outbox 的命令 ID，是本查询面的强制入口。 */
        String commandId,

        /** task-management 任务 ID。该字段是低敏关联键，用于从 Agent 侧跳转或对账任务中心。 */
        Long taskId,

        /** task-management 任务运行 ID，用于区分同一任务的多次执行、重试或补偿。 */
        Long taskRunId,

        /** worker 执行器 ID。这里只展示服务账号/worker 编码，不展示主机、容器或内部地址。 */
        String executorId,

        /** Agent 工具审计 ID，用于把 worker receipt 与 Agent 审计状态回调串联。 */
        String auditId,

        /** 租户边界，已经经过 gateway/permission-admin Header 与请求参数求交集。 */
        String tenantId,

        /** 项目边界，用于 PROJECT 数据范围、项目负责人视图和后续审计过滤。 */
        String projectId,

        /** 操作者或服务账号 ID，用于 SELF 范围、actor 级恢复校验和排障归因。 */
        String actorId,

        /** Agent run ID，用于避免跨运行采信同一个 commandId 的旧 receipt。 */
        String runId,

        /** Agent session ID，用于会话级恢复和 timeline 聚合。 */
        String sessionId,

        /** 低敏工具编码。它只表示工具目录身份，不代表工具参数或目标地址。 */
        String toolCode,

        /** task-management 侧任务状态摘要，例如 RUNNING、FAILED 或 COMPLETED。 */
        String taskStatus,

        /** dry-run/worker receipt 的机器结果，例如 DRY_RUN_PASSED 或 FAILED_PRECHECK。 */
        String outcome,

        /** 执行前检查是否通过。false 不一定表示永久失败，也可能是等待审批、澄清或预算。 */
        Boolean preCheckPassed,

        /** 是否已产生真实副作用。当前 controlled dry-run receipt 应保持 false。 */
        Boolean sideEffectExecuted,

        /** 低敏机器错误码，供管理台聚合和推荐下一步动作，不包含人类 message。 */
        String errorCode,

        /** Java runtime event projection 分配的稳定回放游标，用于判断 receipt 新旧。 */
        Long replaySequence,

        /** receipt 被 Java 控制面接收或消费的时间。 */
        Instant consumedAt,

        /** receipt 索引写入或刷新时间，后续 TTL、归档和审计查询会使用它。 */
        Instant indexedAt,

        /** 本视图遵循的 payload 策略，明确提醒调用方这里不是原始工具上下文。 */
        String payloadPolicy
) {
}
