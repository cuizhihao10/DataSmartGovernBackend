/**
 * @Author : Cui
 * @Date: 2026/06/20 23:20
 * @Description DataSmart Govern Backend - DataSyncAgentCommandReceiptStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.agent;

import com.czh.datasmart.govern.datasource.entity.DataSyncAgentCommandReceipt;

import java.util.Optional;

/**
 * Agent 命令 receipt 存储端口。
 *
 * <p>服务层依赖这个小接口，而不是直接依赖 MyBatis Mapper，是为了降低耦合：
 * 幂等编排只关心“能否按 command/idempotency 查、能否插入、能否更新”，不需要知道 SQL 或 ORM 细节。
 * 单元测试也可以用内存实现验证业务规则，避免为了一个跨服务编排测试启动 Spring/MyBatis。</p>
 */
public interface DataSyncAgentCommandReceiptStore {

    /**
     * 按 commandId 或 idempotencyKey 查询已有 receipt。
     *
     * <p>这两个字段任意一个命中都说明当前请求可能是重复投递或幂等冲突，需要由服务层继续校验绑定关系。</p>
     */
    Optional<DataSyncAgentCommandReceipt> findByCommandOrIdempotencyKey(String commandId, String idempotencyKey);

    /**
     * 插入 receipt，用于抢占幂等键。
     */
    void insert(DataSyncAgentCommandReceipt receipt);

    /**
     * 更新 receipt，用于写入下游 syncTaskId、状态和结果说明。
     */
    void updateById(DataSyncAgentCommandReceipt receipt);
}
