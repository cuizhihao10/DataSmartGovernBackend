/**
 * @Author : Cui
 * @Date: 2026/06/17 00:00
 * @Description DataSmart Govern Backend - AgentToolActionResumeLocatorIndexStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import java.util.Optional;

/**
 * 工具动作恢复 locator index 仓储协议。
 *
 * <p>该接口是后续 MySQL durable locator index 的替换点。当前第一版内存实现可以满足本地联调和单测，
 * 但生产环境需要把 checkpointId/threadId 与 command/outbox/approval/receipt 的映射落到 durable store，
 * 否则服务重启或多实例部署后仍无法只凭 checkpoint/thread 恢复定位。</p>
 */
public interface AgentToolActionResumeLocatorIndexStore {

    /**
     * 写入或合并 locator 记录。
     *
     * <p>同一个 checkpoint/thread 可能会在多次请求中逐步补齐不同定位符，所以 store 需要支持合并，而不是简单覆盖。</p>
     */
    void upsert(AgentToolActionResumeLocatorIndexRecord record);

    /** 按 checkpointId 精确查询 locator 记录。 */
    Optional<AgentToolActionResumeLocatorIndexRecord> findByCheckpointId(String checkpointId);

    /** 按 threadId 查询最近学习到的 locator 记录。 */
    Optional<AgentToolActionResumeLocatorIndexRecord> findByThreadId(String threadId);

    /** 返回索引中的记录数量，用于测试、诊断和后续指标化。 */
    int size();
}
