/**
 * @Author : Cui
 * @Date: 2026/05/28 18:00
 * @Description DataSmart Govern Backend - AgentToolExecutionEventOutboxStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.event.outbox;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Agent 工具执行事件 outbox 仓储协议。
 *
 * <p>这里先定义接口，再提供内存实现，是为了把“outbox 领域语义”和“具体存储技术”拆开。
 * 当前可以用内存热窗口完成联调和单元测试；后续替换成 MySQL 表、Redis Stream、Kafka compacted topic 或审计中心表时，
 * 上层 sink、查询服务和 dispatcher 不需要改业务逻辑。</p>
 */
public interface AgentToolExecutionEventOutboxStore {

    /**
     * 追加一条 outbox 记录。
     *
     * @param record 已构造好的待投递或已阻断记录。
     * @return true 表示首次写入；false 表示 outboxId 已存在，本次属于重复事件。
     */
    boolean append(AgentToolExecutionEventOutboxRecord record);

    /**
     * 按 outboxId 查找记录。
     */
    Optional<AgentToolExecutionEventOutboxRecord> findByOutboxId(String outboxId);

    /**
     * 查询 outbox 记录。
     *
     * <p>runId 为空时查询全局热窗口；status 为空时不过滤状态。
     * 生产数据库实现应把 runId/status/create_time 建成组合索引，避免全表扫描。</p>
     */
    List<AgentToolExecutionEventOutboxRecord> list(String runId,
                                                   AgentToolExecutionEventOutboxStatus status,
                                                   int limit);

    /**
     * 查询已经到达重试时间的待投递记录。
     *
     * <p>后续 dispatcher 会使用该方法领取 PENDING/FAILED 记录。
     * BLOCKED 不会被自动返回，因为它通常代表 payload 或契约问题，需要人工或修复后再处理。</p>
     */
    List<AgentToolExecutionEventOutboxRecord> listPublishable(int limit, Instant now);

    /**
     * 标记事件被投递器领取。
     */
    Optional<AgentToolExecutionEventOutboxRecord> markPublishing(String outboxId, Instant now);

    /**
     * 标记事件投递成功。
     */
    Optional<AgentToolExecutionEventOutboxRecord> markPublished(String outboxId, Instant now);

    /**
     * 标记事件投递失败并等待重试。
     */
    Optional<AgentToolExecutionEventOutboxRecord> markFailed(String outboxId,
                                                            String error,
                                                            Instant now,
                                                            Instant nextRetryAt);

    /**
     * 返回 outbox 诊断摘要。
     */
    AgentToolExecutionEventOutboxDiagnostics diagnostics();
}
