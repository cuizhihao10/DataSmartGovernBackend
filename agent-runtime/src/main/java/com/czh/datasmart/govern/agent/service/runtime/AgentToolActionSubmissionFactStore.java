/**
 * @Author : Cui
 * @Date: 2026/06/28 21:45
 * @Description DataSmart Govern Backend - AgentToolActionSubmissionFactStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import java.util.Optional;

/**
 * Agent 受控工具真实提交事实仓储端口。
 *
 * <p>该端口隔离“提交事实存在哪里”的细节。默认内存实现适合本地学习和单测；
 * MySQL 实现适合多实例、跨重启和运营审计。业务服务只依赖这个端口，
 * 避免把 JDBC、锁、唯一索引和序列化细节耦合进工具提交流程。</p>
 */
public interface AgentToolActionSubmissionFactStore {

    /**
     * 按 commandId 查询当前提交事实。
     *
     * @param commandId command outbox ID。
     * @return 已存在的提交事实；为空表示该 command 尚未开始真实提交。
     */
    Optional<AgentToolActionSubmissionFactRecord> findByCommandId(String commandId);

    /**
     * 原子登记 SUBMITTING 事实。
     *
     * <p>这是防重复提交的关键方法。实现必须保证同一个 commandId 只有一个调用者能获得
     * started=true。内存实现可以 synchronized，MySQL 实现应使用事务、行锁或唯一索引。</p>
     *
     * @param candidate 待登记的 SUBMITTING 事实。
     * @return 登记结果。
     */
    AgentToolActionSubmissionFactStartResult start(AgentToolActionSubmissionFactRecord candidate);

    /**
     * 保存提交事实终态或未知态。
     *
     * <p>该方法用于把 SUBMITTING 更新为 SUBMITTED、REJECTED 或 UNKNOWN。
     * 它不能写入 payload body、prompt、SQL、模型输出或内部 URL。</p>
     *
     * @param record 更新后的低敏提交事实。
     * @return 保存后的事实记录。
     */
    AgentToolActionSubmissionFactRecord save(AgentToolActionSubmissionFactRecord record);
}
