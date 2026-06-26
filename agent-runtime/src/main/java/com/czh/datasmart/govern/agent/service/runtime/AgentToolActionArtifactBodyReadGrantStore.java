/**
 * @Author : Cui
 * @Date: 2026/06/26 20:27
 * @Description DataSmart Govern Backend - AgentToolActionArtifactBodyReadGrantStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import java.util.List;
import java.util.Optional;

/**
 * artifact 正文读取授权事实仓库接口。
 *
 * <p>该接口是 memory -> MySQL/审计中心 的替换边界。业务服务只依赖“保存、按引用回查、撤销”这三个语义，
 * 不依赖 Map、JDBC 或未来 Redis/MySQL 的具体实现。这样后续将 grant fact 做成真正可持久化、可审计、
 * 可跨实例共享的生产能力时，不需要改写 final-check、object-store probe 或 controller 契约。</p>
 */
public interface AgentToolActionArtifactBodyReadGrantStore {

    /**
     * 保存或覆盖一条 grant fact。
     *
     * <p>保存操作必须是低敏写入：实现层不得额外读取 artifact 正文、sample bytes、URL、bucket/key、token、
     * prompt、SQL 或工具参数。对于同一个 grantDecisionReference，重复保存应按幂等覆盖处理，
     * 以兼容 HTTP 重试和未来事件回放补物化。</p>
     */
    void save(AgentToolActionArtifactBodyReadGrantRecord record);

    /**
     * 根据低敏 grant 引用查找服务端事实。
     *
     * <p>final-check 和 object-store probe 必须调用该方法。找不到记录时必须 fail-closed，
     * 不能因为 previousGrantDecisionReference 的字符串格式正确就继续读取。</p>
     */
    Optional<AgentToolActionArtifactBodyReadGrantRecord> findByReference(String grantDecisionReference);

    /**
     * 按低敏条件查询 grant fact。
     *
     * <p>该方法主要服务管理员排障、审计台和后续 TTL 归档预览，不参与 final-check/probe 的关键执行路径。
     * 查询条件必须由服务层先经过数据范围收口后再传入；实现层仍要把 authorizedProjectIds 下沉到过滤或 SQL，
     * 防止只靠 Controller 参数导致跨项目越权。</p>
     */
    List<AgentToolActionArtifactBodyReadGrantRecord> query(
            AgentToolActionArtifactBodyReadGrantQuery query,
            int limit);

    /**
     * 撤销一条 grant fact。
     *
     * <p>当前批次先提供 store 语义，后续可以在管理员 API 中接入。撤销后记录仍保留在仓库中，
     * 用于解释为什么某个旧 grant 在有效期内也被拒绝。</p>
     */
    Optional<AgentToolActionArtifactBodyReadGrantRecord> revoke(
            String grantDecisionReference,
            String operatorId,
            String reasonCode,
            long revokedAtEpochMs);

    /**
     * 当前仓库中的记录数量，用于单测、诊断和后续 Micrometer 指标。
     */
    int size();
}
