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

import java.util.List;

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
     * 目标端主键/冲突键字段列表。
     *
     * <p>这里保存的是字段名，不保存字段值。writer 在隔离脏数据时会用这些字段从当前失败行里提取“主键定位摘要”，
     * 让 data-sync 后续可以把结构化脏样本转成受控的修复重放条件。没有这个字段时，系统只能知道某行失败，
     * 但无法安全地从源端重新读取同一行，修复重放就会停留在控制面。</p>
     */
    private List<String> primaryKeyColumns;

    /**
     * 内部预编译写入语句。
     */
    private SyncPreparedJdbcStatement writeStatement;

    /**
     * 兼容旧测试和旧调用点的构造器。
     *
     * <p>新增 primaryKeyColumns 后，历史调用方如果还没有传主键字段，就默认认为当前写入上下文无法提供
     * 精确主键定位。writer 仍会生成 rowHash/rowIndex 级诊断样本，但 dirty-record replay 会要求后续样本具备
     * PRIMARY_KEY_EQ 定位后才真正执行。</p>
     */
    public SyncBatchWriteContext(Long taskId,
                                 Long executionId,
                                 Long datasourceId,
                                 String writeStrategy,
                                 Integer writeBatchSize,
                                 Integer commitIntervalRecords,
                                 SyncPreparedJdbcStatement writeStatement) {
        this(taskId, executionId, datasourceId, writeStrategy, writeBatchSize, commitIntervalRecords,
                List.of(), writeStatement);
    }
}
