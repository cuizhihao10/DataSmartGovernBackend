/**
 * @Author : Cui
 * @Date: 2026/06/01 00:00
 * @Description DataSmart Govern Backend - AgentRuntimeEventReplayCursorStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import java.util.Optional;

/**
 * Agent runtime event replay cursor 仓储协议。
 *
 * <p>该接口把“客户端 ack 游标存在哪里”从业务服务中隔离出来。当前是内存版本，满足本地学习、单实例联调和
 * WebSocket 协议打样；后续商业化可以替换为 Redis、MySQL 或专门的 session cursor 表，而不需要重写 controller。</p>
 */
public interface AgentRuntimeEventReplayCursorStore {

    /**
     * 查询某个客户端在某个订阅范围内的 cursor。
     */
    Optional<AgentRuntimeEventReplayCursorRecord> find(String clientId, String subscriptionKey);

    /**
     * 保存或推进 cursor。
     *
     * <p>实现必须保证 cursor 不回退。网络重试、WebSocket 重连和浏览器多标签页都可能重复提交旧 ack，
     * 如果旧 ack 覆盖新 ack，客户端下一次 replay 会重复收到大量历史事件。</p>
     */
    CursorAdvanceResult saveMax(AgentRuntimeEventReplayCursorRecord candidate);

    /**
     * cursor 推进结果。
     *
     * @param previous 保存前的旧记录；首次 ack 为空。
     * @param current 保存后的当前记录。
     * @param advanced 本次是否真正推进了 acknowledgedReplaySequence。
     */
    record CursorAdvanceResult(
            Optional<AgentRuntimeEventReplayCursorRecord> previous,
            AgentRuntimeEventReplayCursorRecord current,
            boolean advanced
    ) {
    }
}
