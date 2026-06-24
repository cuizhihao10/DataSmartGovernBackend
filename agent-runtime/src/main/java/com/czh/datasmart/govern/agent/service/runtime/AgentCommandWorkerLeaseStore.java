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
     * 当前持有者续租 command lease。
     *
     * <p>续租必须是原子操作：Store 需要先确认当前记录存在、未过期、executor/token/version 都匹配，
     * 再写入新的过期时间。这样可以避免一个已经失去资格的旧 worker 通过续租把新 worker 的执行窗口覆盖掉。</p>
     *
     * @param candidate 续租后的候选记录，token/version 与当前持有者相同，leaseExpiresAt 为新的过期时间。
     * @param now 当前控制面时间。
     * @return 续租结果；只有 RENEWED 才能向调用方返回 token。
     */
    AgentCommandWorkerLeaseClaimResult renew(AgentCommandWorkerLeaseRecord candidate, Instant now);

    /**
     * 当前持有者释放 command lease。
     *
     * <p>释放不是删除记录，而是把 leaseExpiresAt 更新为当前时间。这样下一次 claim 仍能读取旧版本并递增，
     * 保持 fencing 版本单调，避免释放后重新领取又回到版本 1。</p>
     *
     * @param candidate 当前持有者提交的释放记录，leaseExpiresAt 通常等于 now。
     * @param now 当前控制面时间。
     * @return 释放结果；释放成功也不需要再返回 token。
     */
    AgentCommandWorkerLeaseClaimResult release(AgentCommandWorkerLeaseRecord candidate, Instant now);

    /**
     * 按 session/run/command 查询当前 lease 事实。
     */
    Optional<AgentCommandWorkerLeaseRecord> findByIdentity(String sessionId, String runId, String commandId);
}
