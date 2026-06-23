/**
 * @Author : Cui
 * @Date: 2026/06/24 01:40
 * @Description DataSmart Govern Backend - AgentCommandWorkerLeaseStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import java.time.Instant;
import java.util.Optional;

/**
 * command worker lease 仓储端口。
 *
 * <p>该端口隔离“lease 事实如何保存”：本地默认可以用内存实现，生产可以切到 MySQL/Redis。
 * receipt service 只依赖该端口校验当前 token 是否有效，避免把数据库、Redis 或队列 visibility timeout
 * 细节耦合进回执业务校验。</p>
 */
public interface AgentCommandWorkerLeaseStore {

    /**
     * 原子领取 command lease。
     *
     * @param candidate 新 lease 候选记录，包含调用方 executor、token、版本和过期时间。
     * @param now 当前控制面时间。
     * @return 领取结果。
     */
    AgentCommandWorkerLeaseClaimResult claim(AgentCommandWorkerLeaseRecord candidate, Instant now);

    /**
     * 按 session/run/command 查询当前 lease 事实。
     */
    Optional<AgentCommandWorkerLeaseRecord> findByIdentity(String sessionId, String runId, String commandId);
}
