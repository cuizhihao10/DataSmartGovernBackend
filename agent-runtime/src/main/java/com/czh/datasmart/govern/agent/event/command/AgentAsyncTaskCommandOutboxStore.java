/**
 * @Author : Cui
 * @Date: 2026/05/31 17:08
 * @Description DataSmart Govern Backend - AgentAsyncTaskCommandOutboxStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.event.command;

import java.time.Instant;
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

    List<AgentAsyncTaskCommandOutboxRecord> listPublishable(int limit, Instant now);

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

    AgentAsyncTaskCommandOutboxDiagnostics diagnostics();
}
