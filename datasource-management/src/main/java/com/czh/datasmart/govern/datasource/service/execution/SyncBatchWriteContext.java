/**
 * @Author : Cui
 * @Date: 2026/06/20 03:22
 * @Description DataSmart Govern Backend - SyncBatchWriteContext.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.execution;

import com.czh.datasmart.govern.datasource.service.execution.jdbc.SyncPreparedJdbcStatement;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 批处理写入上下文。
 *
 * <p>该对象描述 writer 执行一批目标端写入所需的控制字段。
 * 它不直接携带行数据，是为了在当前阶段保持低敏和可测试；
 * 后续真实 worker 可以在内存管道、临时文件或受控缓冲区中传递行数据，
 * 但这些数据不应进入普通日志、审计摘要或管理 API。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SyncBatchWriteContext {

    /**
     * 任务 ID。
     */
    private Long taskId;

    /**
     * execution ID。
     */
    private Long executionId;

    /**
     * 目标数据源 ID。
     */
    private Long datasourceId;

    /**
     * 写入策略。
     */
    private String writeStrategy;

    /**
     * 推荐写入批大小。
     */
    private Integer writeBatchSize;

    /**
     * 推荐提交间隔。
     */
    private Integer commitIntervalRecords;

    /**
     * 内部预编译写入语句。
     */
    private SyncPreparedJdbcStatement writeStatement;
}
