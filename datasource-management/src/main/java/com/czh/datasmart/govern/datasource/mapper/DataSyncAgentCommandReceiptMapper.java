/**
 * @Author : Cui
 * @Date: 2026/06/20 23:20
 * @Description DataSmart Govern Backend - DataSyncAgentCommandReceiptMapper.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czh.datasmart.govern.datasource.entity.DataSyncAgentCommandReceipt;
import org.apache.ibatis.annotations.Mapper;

/**
 * Agent 命令 receipt Mapper。
 *
 * <p>当前只需要 MyBatis-Plus BaseMapper 提供的 insert、selectOne 和 updateById。
 * 查询条件由仓储类统一封装，避免服务层到处拼装 commandId/idempotencyKey 查询规则。</p>
 */
@Mapper
public interface DataSyncAgentCommandReceiptMapper extends BaseMapper<DataSyncAgentCommandReceipt> {
}
