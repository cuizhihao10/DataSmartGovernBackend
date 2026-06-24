/**
 * @Author : Cui
 * @Date: 2026/06/24 23:40
 * @Description DataSmart Govern Backend - AgentAsyncTaskCommandOutboxOperationStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.event.command;

import java.time.Instant;
import java.util.Optional;

/**
 * Agent 异步命令 outbox 人工补偿仓储端口。
 *
 * <p>它与 {@link AgentAsyncTaskCommandOutboxStore} 分开，是为了避免主 Store 同时承载入箱、dispatcher 领取、
 * 投递成功/失败、stale 恢复和人工补偿动作。command outbox 是未来真实 sandbox runner、task-management
 * Inbox、dead-letter 管理台之间的关键边界，如果所有状态变更都堆在一个 Impl 里，很快会超过 500 行并且难以学习。</p>
 *
 * <p>本端口只允许处理低敏状态变更，不读取或返回 payloadJson。调用方应该先通过服务层做权限、状态和 reason
 * 校验，再进入这里执行条件更新。这样后续接入 permission-admin、双人复核、operation_audit 表或批量 dry-run 时，
 * 可以主要扩展服务层与该端口，不影响 dispatcher 的自动投递路径。</p>
 */
public interface AgentAsyncTaskCommandOutboxOperationStore {

    /**
     * 将 FAILED/BLOCKED/DEAD_LETTER 命令重新放回 PENDING。
     *
     * @param outboxId outbox 记录 ID。
     * @param reason 低敏人工操作原因，会写入 lastError 最近摘要。
     * @param now 操作时间。
     * @param nextRetryAt 可选重排时间；为空表示立即允许 dispatcher 领取。
     * @return 更新后的安全记录；状态不允许或记录不存在时返回 empty。
     */
    Optional<AgentAsyncTaskCommandOutboxRecord> markRequeued(String outboxId,
                                                             String reason,
                                                             Instant now,
                                                             Instant nextRetryAt);

    /**
     * 将 FAILED/BLOCKED 命令转入 DEAD_LETTER。
     */
    Optional<AgentAsyncTaskCommandOutboxRecord> markDeadLetter(String outboxId, String reason, Instant now);

    /**
     * 将 FAILED/BLOCKED/DEAD_LETTER 命令人工忽略。
     */
    Optional<AgentAsyncTaskCommandOutboxRecord> markIgnored(String outboxId, String reason, Instant now);

    /**
     * 为非 PUBLISHED 命令追加人工备注。
     */
    Optional<AgentAsyncTaskCommandOutboxRecord> appendOperationNote(String outboxId, String reason, Instant now);
}
