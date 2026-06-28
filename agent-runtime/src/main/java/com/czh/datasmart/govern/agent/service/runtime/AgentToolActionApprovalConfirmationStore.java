/**
 * @Author : Cui
 * @Date: 2026/06/28 22:20
 * @Description DataSmart Govern Backend - AgentToolActionApprovalConfirmationStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import java.time.Instant;
import java.util.Optional;

/**
 * 工具动作审批确认事实仓储端口。
 *
 * <p>这里先定义接口，是为了让“确认事实怎么保存”和“writer 如何校验确认事实”解耦。当前内存实现用于本地学习和
 * 单实例闭环；生产版可以替换为 MySQL 审批事实表、Redis 短租约、permission-admin 审批单映射或审计归档存储。
 * writer/verifier 只依赖本端口，不应该知道底层持久化方式。</p>
 */
public interface AgentToolActionApprovalConfirmationStore {

    /**
     * 幂等保存确认事实。
     *
     * <p>同一个 confirmationId 应稳定指向同一份确认元数据。重复保存返回 false，便于前端确认页或网关因网络问题重试时
     * 复用旧记录，而不是创建多个含义相同的确认事实。</p>
     */
    boolean saveIfAbsent(AgentToolActionApprovalConfirmationRecord record);

    /**
     * 按 confirmationId 查询确认事实。
     *
     * <p>查询结果只包含低敏元数据，不包含 payload body。writer 用它判断确认是否存在、是否过期、是否绑定当前 run/tool。</p>
     */
    Optional<AgentToolActionApprovalConfirmationRecord> findByConfirmationId(String confirmationId);

    /**
     * 清理过期确认事实。
     *
     * <p>内存实现需要显式清理；生产实现可以映射为 SQL delete、Redis TTL 或归档任务。</p>
     */
    int removeExpired(Instant now);
}
