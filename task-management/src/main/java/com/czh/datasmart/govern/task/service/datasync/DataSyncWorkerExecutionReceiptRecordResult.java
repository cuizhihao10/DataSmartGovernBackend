/**
 * @Author : Cui
 * @Date: 2026/06/22 10:35
 * @Description DataSmart Govern Backend - DataSyncWorkerExecutionReceiptRecordResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.datasync;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DataSync worker 执行回执写入结果。
 *
 * @param schemaVersion 响应契约版本，便于后续管理台或消费者判断字段兼容性。
 * @param accepted true 表示回执已被接收；duplicate=true 时表示幂等复用已有记录。
 * @param duplicate true 表示 receiptId 已存在，本次没有重复插入。
 * @param receivedAt task-management 收到并处理回执的时间。
 * @param record 低敏回执视图。
 * @param warnings 低敏提示，不包含 SQL、连接串、工具参数、样本数据、prompt 或内部 endpoint。
 */
public record DataSyncWorkerExecutionReceiptRecordResult(
        String schemaVersion,
        boolean accepted,
        boolean duplicate,
        LocalDateTime receivedAt,
        DataSyncWorkerExecutionReceiptView record,
        List<String> warnings
) {
}
