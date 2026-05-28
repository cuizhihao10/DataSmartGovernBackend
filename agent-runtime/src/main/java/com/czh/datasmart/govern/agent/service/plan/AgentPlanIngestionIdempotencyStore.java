/**
 * @Author : Cui
 * @Date: 2026/05/24 02:46
 * @Description DataSmart Govern Backend - AgentPlanIngestionIdempotencyStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.plan;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * AgentPlan 接入幂等内存仓储。
 *
 * <p>这个仓储是 Kafka 异步接入前的过渡实现。
 * 它让当前 HTTP 接入口先具备重复提交保护，同时把未来持久化幂等表的职责边界固定下来。
 *
 * <p>生产化替换方向：
 * 1. MySQL：以 `tenant_id + project_id + actor_id + idempotency_key` 建唯一索引，适合强一致审计；
 * 2. Redis：适合短 TTL、高吞吐的快速去重，但需要注意持久化和故障恢复；
 * 3. Kafka Consumer：消费前先占位 PROCESSING，成功后写 SUCCEEDED，失败后按错误类型决定重试或 DLQ。
 */
@Component
public class AgentPlanIngestionIdempotencyStore {

    private final ConcurrentMap<String, AgentPlanIngestionIdempotencyRecord> records = new ConcurrentHashMap<>();

    public Optional<AgentPlanIngestionIdempotencyRecord> findByKey(String dedupeKey) {
        return Optional.ofNullable(records.get(dedupeKey));
    }

    public void save(AgentPlanIngestionIdempotencyRecord record) {
        records.put(record.dedupeKey(), record);
    }
}
