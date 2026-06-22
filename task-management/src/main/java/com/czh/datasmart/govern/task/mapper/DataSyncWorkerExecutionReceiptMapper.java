/**
 * @Author : Cui
 * @Date: 2026/06/22 10:32
 * @Description DataSmart Govern Backend - DataSyncWorkerExecutionReceiptMapper.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czh.datasmart.govern.task.entity.DataSyncWorkerExecutionReceipt;
import org.apache.ibatis.annotations.Mapper;

/**
 * DataSync worker 执行回执 Mapper。
 *
 * <p>Mapper 层只提供 MyBatis-Plus CRUD 能力，不承载幂等、脱敏、outbox 关联或事件类型归一化。
 * 这些业务规则放在 Service 层，能让数据库访问保持薄而稳定，也方便单元测试通过 Mock 验证业务语义。</p>
 */
@Mapper
public interface DataSyncWorkerExecutionReceiptMapper extends BaseMapper<DataSyncWorkerExecutionReceipt> {
}
