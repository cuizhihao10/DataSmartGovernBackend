/**
 * @Author : Cui
 * @Date: 2026/06/01 00:01
 * @Description DataSmart Govern Backend - InMemoryAgentRuntimeEventReplayCursorStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 内存版 runtime event replay cursor 仓储。
 *
 * <p>当前仓储是“热窗口 cursor”而不是最终生产持久化方案。它的价值在于先固定服务端 ack 语义：
 * - cursor 按 clientId + subscriptionKey 隔离；
 * - 重复 ack 幂等；
 * - 旧 ack 不会让游标回退；
 * - 查询和写入线程安全。</p>
 *
 * <p>生产演进方向：</p>
 * <p>1. 单实例学习环境可以继续使用内存；</p>
 * <p>2. 多实例 WebSocket 应迁移到 Redis，并设置 TTL 防止废弃客户端长期占用内存；</p>
 * <p>3. 审计型客户可以落 MySQL，保留客户端消费关键事件的证据。</p>
 */
@Component
public class InMemoryAgentRuntimeEventReplayCursorStore implements AgentRuntimeEventReplayCursorStore {

    private final Map<String, AgentRuntimeEventReplayCursorRecord> recordsByKey = new LinkedHashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    @Override
    public Optional<AgentRuntimeEventReplayCursorRecord> find(String clientId, String subscriptionKey) {
        lock.readLock().lock();
        try {
            return Optional.ofNullable(recordsByKey.get(keyOf(clientId, subscriptionKey)));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public CursorAdvanceResult saveMax(AgentRuntimeEventReplayCursorRecord candidate) {
        lock.writeLock().lock();
        try {
            String key = keyOf(candidate.clientId(), candidate.subscriptionKey());
            AgentRuntimeEventReplayCursorRecord previous = recordsByKey.get(key);
            if (previous != null
                    && previous.acknowledgedReplaySequence() != null
                    && candidate.acknowledgedReplaySequence() <= previous.acknowledgedReplaySequence()) {
                return new CursorAdvanceResult(Optional.of(previous), previous, false);
            }
            recordsByKey.put(key, candidate);
            return new CursorAdvanceResult(Optional.ofNullable(previous), candidate, true);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private String keyOf(String clientId, String subscriptionKey) {
        return clientId + "|" + subscriptionKey;
    }
}
