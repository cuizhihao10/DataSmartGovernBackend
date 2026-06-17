/**
 * @Author : Cui
 * @Date: 2026/06/17 00:00
 * @Description DataSmart Govern Backend - AgentToolActionClarificationFactStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import java.util.Optional;

/**
 * Agent 工具动作澄清事实仓储接口。
 *
 * <p>该接口是一个非常重要的解耦点：恢复预检服务只关心“能否按 factId 找到低敏事实记录”，
 * 不关心事实当前来自内存、MySQL、Redis、审计中心还是未来的专用 HITL 服务。
 * 这样我们可以先用内存实现把业务闭环跑通，后续再替换为 MySQL durable store，而不需要重写
 * fact bundle、controller 或 Python Runtime 的调用契约。</p>
 */
public interface AgentToolActionClarificationFactStore {

    /**
     * 保存或更新澄清事实。
     *
     * <p>实现类应把 {@code clarificationFactId} 当作幂等键。
     * 对同一个 factId 的重复登记应合并为一条记录，而不是生成多条事实，避免前端重试或网关重放导致恢复预检结果不稳定。</p>
     *
     * @param record 低敏澄清事实记录；不得包含澄清正文、prompt、SQL、工具参数或模型输出。
     */
    void upsert(AgentToolActionClarificationFactRecord record);

    /**
     * 按澄清事实 ID 查询记录。
     *
     * <p>仓储层只做主键查询，不做权限裁决。租户、项目、actor、run、session、command、tool
     * 等可见性校验统一放在 {@link AgentToolActionClarificationFactEvaluator}，避免不同存储实现产生不同安全语义。</p>
     */
    Optional<AgentToolActionClarificationFactRecord> findByFactId(String clarificationFactId);

    /**
     * 返回当前仓储记录数量。
     *
     * <p>该方法主要用于本地诊断和单元测试。生产 MySQL 实现后不建议在高频路径调用全表 count，
     * 应改用低基数指标或近似统计。</p>
     */
    int size();
}
