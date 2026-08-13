/**
 * @Author : Cui
 * @Date: 2026/05/31 17:08
 * @Description DataSmart Govern Backend - AgentAsyncTaskCommandOutboxStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.event.command;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Agent 异步命令 outbox 仓储协议。
 *
 * <p>先定义端口再提供内存实现，是为了让后续 MySQL store 成为增量替换。
 * 上层 enqueue、dispatcher、query 和诊断能力都依赖该接口，不依赖具体存储技术。</p>
 */
public interface AgentAsyncTaskCommandOutboxStore {

    /**
     * 追加记录。
     *
     * @return true 表示首次写入；false 表示 outboxId/commandId 已存在。
     */
    boolean append(AgentAsyncTaskCommandOutboxRecord record);

    Optional<AgentAsyncTaskCommandOutboxRecord> findByOutboxId(String outboxId);

    Optional<AgentAsyncTaskCommandOutboxRecord> findByCommandId(String commandId);

    List<AgentAsyncTaskCommandOutboxRecord> list(String runId,
                                                 AgentAsyncTaskCommandOutboxStatus status,
                                                 int limit);

    /**
     * 统计某个 run 在指定状态集合下的 outbox 记录数。
     *
     * <p>该方法主要服务入箱前容量保护：selected-node 或 Run 级批量入口在写入新 command 前，
     * 需要先知道当前 run 是否已经有大量 PENDING/PUBLISHING/FAILED 命令等待处理。
     * 这里用 count 而不是 list，是为了让 MySQL 实现可以走数据库聚合，避免为了判断阈值拉回大量 payload。</p>
     */
    long countByRunAndStatuses(String runId, Collection<AgentAsyncTaskCommandOutboxStatus> statuses);

    /**
     * 统计某个租户在指定状态集合下的 outbox 记录数。
     *
     * <p>这是第一版租户级 backlog 保护的底座。后续如果接入 Redis 分布式限流或独立 quota-center，
     * 上层 Guard 仍可保持同一个语义：在产生更多后台副作用之前，先判断租户是否已经积压过多活跃 command。</p>
     */
    long countByTenantAndStatuses(Long tenantId, Collection<AgentAsyncTaskCommandOutboxStatus> statuses);

    List<AgentAsyncTaskCommandOutboxRecord> listPublishable(int limit, Instant now);

    /**
     * 按工具白名单查询当前可被 dispatcher 领取的记录。
     *
     * <p>默认实现用于兼容测试替身和较早的 Store 扩展；正式内存/JDBC Store 会在存储层先过滤再分页，
     * 防止大量未开放工具排在队首时饿死已经获准自动派发的命令。空白名单表示保持历史行为并查询所有工具。</p>
     *
     * @param allowedToolCodes 允许自动派发的工具码；空集合表示不限制
     * @param limit            单轮最大记录数
     * @param now              判断重试时间是否到期的参考时间
     * @return 同时满足状态、到期时间和工具白名单的记录
     */
    default List<AgentAsyncTaskCommandOutboxRecord> listPublishableByToolCodes(
            Collection<String> allowedToolCodes,
            int limit,
            Instant now) {
        if (allowedToolCodes == null || allowedToolCodes.isEmpty()) {
            return listPublishable(limit, now);
        }
        return listPublishable(limit, now).stream()
                .filter(record -> allowedToolCodes.contains(record.toolCode()))
                .toList();
    }

    Optional<AgentAsyncTaskCommandOutboxRecord> markPublishing(String outboxId, Instant now);

    Optional<AgentAsyncTaskCommandOutboxRecord> markPublished(String outboxId, Instant now);

    Optional<AgentAsyncTaskCommandOutboxRecord> markFailed(String outboxId,
                                                          String error,
                                                          Instant now,
                                                          Instant nextRetryAt);

    Optional<AgentAsyncTaskCommandOutboxRecord> markBlocked(String outboxId,
                                                           String error,
                                                           Instant now);

    int recoverStalePublishing(Instant staleBefore,
                               Instant now,
                               String error);

    /**
     * 只恢复工具白名单范围内长时间停留在 PUBLISHING 的记录。
     *
     * <p>开启一个灰度 worker 时，恢复动作也必须受同一白名单约束；否则 dispatcher 虽然不会主动领取旧工具，
     * 却可能先把它们从 PUBLISHING 改回 FAILED，造成额外状态扰动。空集合表示保持历史全量恢复行为。</p>
     */
    default int recoverStalePublishingByToolCodes(Collection<String> allowedToolCodes,
                                                   Instant staleBefore,
                                                   Instant now,
                                                   String error) {
        if (allowedToolCodes == null || allowedToolCodes.isEmpty()) {
            return recoverStalePublishing(staleBefore, now, error);
        }
        return 0;
    }

    AgentAsyncTaskCommandOutboxDiagnostics diagnostics();
}
