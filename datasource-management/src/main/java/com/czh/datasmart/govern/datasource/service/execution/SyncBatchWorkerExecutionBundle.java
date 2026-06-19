/**
 * @Author : Cui
 * @Date: 2026/06/20 03:22
 * @Description DataSmart Govern Backend - SyncBatchWorkerExecutionBundle.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.execution;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * worker 执行准备包。
 *
 * <p>这是 `SyncBatchExecutionPlan` 进入真实 worker 前的内部装配结果。
 * 它包含读取上下文、写入上下文、checkpoint 计划、回调计划、警告和安全边界。
 * 其中 read/write context 内部可能包含 SQL 模板，因此该对象不能作为普通 API 返回。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SyncBatchWorkerExecutionBundle {

    /**
     * 执行准备包版本。
     */
    private String bundleVersion;

    /**
     * 安全边界说明。
     */
    private String executionBoundary;

    /**
     * 任务 ID。
     */
    private Long taskId;

    /**
     * execution ID。
     */
    private Long executionId;

    /**
     * 读取上下文。
     */
    private SyncBatchReadContext readContext;

    /**
     * 写入上下文。
     */
    private SyncBatchWriteContext writeContext;

    /**
     * checkpoint 计划。
     */
    private SyncBatchWorkerCheckpointPlan checkpointPlan;

    /**
     * worker 回调计划。
     */
    private SyncBatchWorkerCallbackPlan callbackPlan;

    /**
     * 执行前警告。
     */
    private List<String> warnings;

    /**
     * 生成时间。
     */
    private LocalDateTime generatedAt;
}
