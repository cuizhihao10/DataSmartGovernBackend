/**
 * @Author : Cui
 * @Date: 2026/06/20 23:20
 * @Description DataSmart Govern Backend - DataSyncAgentCommandReceiptRepository.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czh.datasmart.govern.datasource.entity.DataSyncAgentCommandReceipt;
import com.czh.datasmart.govern.datasource.mapper.DataSyncAgentCommandReceiptMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Agent 命令 receipt 的 MyBatis-Plus 仓储实现。
 *
 * <p>仓储层只负责持久化访问，不承载“重复命令应该返回什么”“冲突命令应该如何拒绝”这类业务决策。
 * 这些决策保留在 DataSyncAgentTaskExecutionService 中，便于阅读完整的命令接收流程。</p>
 */
@Repository
@RequiredArgsConstructor
public class DataSyncAgentCommandReceiptRepository implements DataSyncAgentCommandReceiptStore {

    private final DataSyncAgentCommandReceiptMapper receiptMapper;

    @Override
    public Optional<DataSyncAgentCommandReceipt> findByCommandOrIdempotencyKey(String commandId, String idempotencyKey) {
        LambdaQueryWrapper<DataSyncAgentCommandReceipt> wrapper =
                new LambdaQueryWrapper<DataSyncAgentCommandReceipt>()
                        .eq(DataSyncAgentCommandReceipt::getCommandId, commandId)
                        .or()
                        .eq(DataSyncAgentCommandReceipt::getIdempotencyKey, idempotencyKey)
                        .last("LIMIT 1");
        return Optional.ofNullable(receiptMapper.selectOne(wrapper));
    }

    @Override
    public void insert(DataSyncAgentCommandReceipt receipt) {
        receiptMapper.insert(receipt);
    }

    @Override
    public void updateById(DataSyncAgentCommandReceipt receipt) {
        receiptMapper.updateById(receipt);
    }
}
