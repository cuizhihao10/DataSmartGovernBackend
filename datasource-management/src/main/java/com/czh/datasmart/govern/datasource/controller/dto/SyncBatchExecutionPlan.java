/**
 * @Author : Cui
 * @Date: 2026/06/20 02:42
 * @Description DataSmart Govern Backend - SyncBatchExecutionPlan.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 同步批处理执行计划。
 *
 * <p>该 DTO 是 datasource-management 从“任务控制面”走向“真实执行器契约”的关键对象。
 * 过去执行器认领任务后只能拿到任务 ID、模板 ID、源端对象、目标端对象等基础字段；
 * 但真正的 worker 还需要知道：本次按什么模式读取、按什么策略写入、checkpoint 保存什么、心跳和回调如何处理。
 * 这些信息如果让 worker 自己推断，就会造成多个执行器实现各自解释同步语义，后续很难保证一致性和可审计性。</p>
 *
 * <p>安全边界：</p>
 * <p>1. 本计划不返回 JDBC URL、用户名、密码、完整 SQL、where 条件正文、样本数据或真实业务数据；</p>
 * <p>2. 本计划只返回低敏控制字段，例如连接器类型、数据源 ID、对象定位符、同步模式、批量大小建议、checkpoint 类型；</p>
 * <p>3. 如果未来 worker 需要生成真实 SQL，应由 worker 在受控连接器实现内基于该计划生成，不应把 SQL 暴露给普通控制台。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SyncBatchExecutionPlan {

    /**
     * 执行计划版本。
     * 后续如果计划字段发生兼容性变化，worker 可以根据版本做灰度兼容。
     */
    private String planVersion;

    /**
     * 执行边界说明。
     * 用于明确该计划只描述批处理执行契约，不代表任务已经完成或数据已经被写入。
     */
    private String executionBoundary;

    /**
     * 执行计划适用的任务 ID。
     */
    private Long taskId;

    /**
     * 执行计划适用的 execution ID。
     */
    private Long executionId;

    /**
     * 读取计划。
     * 描述源端如何被读取，例如全量扫描、时间增量窗口、ID 增量范围、回放或补数。
     */
    private ReadPlan readPlan;

    /**
     * 写入计划。
     * 描述目标端如何接收数据，例如追加、幂等 upsert、冲突忽略或覆盖写入。
     */
    private WritePlan writePlan;

    /**
     * 检查点计划。
     * 描述本次执行需要保存什么类型的断点，以及 worker 应按什么粒度提交 checkpoint。
     */
    private CheckpointPlan checkpointPlan;

    /**
     * 运行控制计划。
     * 描述心跳、租约、超时、重试和回调这些控制面协议。
     */
    private RuntimeControlPlan runtimeControlPlan;

    /**
     * 执行计划警告。
     * 这些警告不一定阻断任务，但会提示 worker 或运维关注性能、幂等、分区、生产边界等问题。
     */
    private List<String> warnings;

    /**
     * 计划生成时间。
     */
    private LocalDateTime generatedAt;

    /**
     * 批处理读取计划。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReadPlan {

        /**
         * 源端连接器类型，例如 MYSQL、POSTGRESQL、SQLSERVER。
         */
        private String connectorType;

        /**
         * 源端数据源 ID。
         * 这里只返回 ID，不返回连接地址和凭证，worker 需要通过受控数据源服务读取连接配置。
         */
        private Long datasourceId;

        /**
         * 源端对象定位符。
         * 通常为 schema.table 形式，仅用于定位对象，不包含 SQL 条件或样本数据。
         */
        private String objectLocator;

        /**
         * 读取策略。
         * 例如 FULL_OBJECT_SCAN、INCREMENTAL_TIME_WINDOW、INCREMENTAL_ID_RANGE、REPLAY_FROM_CHECKPOINT。
         */
        private String readStrategy;

        /**
         * 同步模式。
         * 直接来自模板，用于 worker 判断完成语义和 checkpoint 语义。
         */
        private String syncMode;

        /**
         * 增量字段。
         * 仅返回字段名，不返回具体水位值；真实水位应由 checkpoint 表或 worker 内部状态控制。
         */
        private String incrementalField;

        /**
         * 是否配置了分区/分片。
         * 当前不回传 partitionConfig 原文，避免把复杂过滤条件或业务范围暴露到 claim 响应中。
         */
        private Boolean partitionConfigured;

        /**
         * 推荐 JDBC fetchSize。
         * 它是控制面给 worker 的性能建议，不是强制值。
         */
        private Integer recommendedFetchSize;

        /**
         * worker 应具备的读取侧能力。
         * 例如 JDBC_BATCH_READ、CHECKPOINT_AWARE_READ。
         */
        private List<String> requiredWorkerCapabilities;
    }

    /**
     * 批处理写入计划。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WritePlan {

        /**
         * 目标端连接器类型。
         */
        private String connectorType;

        /**
         * 目标端数据源 ID。
         */
        private Long datasourceId;

        /**
         * 目标端对象定位符。
         */
        private String objectLocator;

        /**
         * 写入策略。
         */
        private String writeStrategy;

        /**
         * 冲突处理策略。
         * 该字段把写入策略翻译为 worker 更容易理解的执行语义。
         */
        private String conflictPolicy;

        /**
         * 是否需要主键或唯一键。
         * UPSERT、INSERT_IGNORE、REPLACE 等策略通常需要目标端可判定冲突。
         */
        private Boolean primaryKeyRequired;

        /**
         * 主键/冲突字段。
         * 仅返回字段名，不返回具体数据值。
         */
        private String primaryKeyField;

        /**
         * 推荐写入批大小。
         */
        private Integer recommendedWriteBatchSize;

        /**
         * 推荐提交间隔。
         * 当前用记录数表达，后续可扩展时间窗口、分区提交或事务策略。
         */
        private Integer recommendedCommitIntervalRecords;

        /**
         * worker 应具备的写入侧能力。
         */
        private List<String> requiredWorkerCapabilities;
    }

    /**
     * 检查点计划。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CheckpointPlan {

        /**
         * 检查点类型，例如 TIME_WATERMARK、ID_WATERMARK、PARTITION_RANGE_CURSOR。
         */
        private String checkpointType;

        /**
         * 初始检查点策略。
         * 用于告诉 worker 没有历史 checkpoint 时如何处理。
         */
        private String initialCheckpointPolicy;

        /**
         * 是否要求恢复能力。
         */
        private Boolean resumeRequired;

        /**
         * 是否按分片/分区保存 checkpoint。
         */
        private Boolean shardAware;

        /**
         * 建议每处理多少条记录保存一次 checkpoint。
         */
        private Integer persistEveryRecords;

        /**
         * checkpoint 值可见性。
         * 这里明确 checkpoint 真实值应保留在执行器和 checkpoint 表，不应进入普通 timeline 或审计摘要。
         */
        private String checkpointValueVisibility;
    }

    /**
     * 运行控制计划。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RuntimeControlPlan {

        /**
         * 执行器实例标识。
         */
        private String executorId;

        /**
         * 当前租约过期时间。
         */
        private LocalDateTime leaseExpireAt;

        /**
         * 是否需要心跳续租。
         */
        private Boolean heartbeatRequired;

        /**
         * 超时时间，单位秒。
         */
        private Integer timeoutSeconds;

        /**
         * 最大重试次数。
         */
        private Integer maxRetryCount;

        /**
         * 幂等范围说明。
         * worker 可以用 taskId + executionId + shard 组织幂等键，避免重复写入。
         */
        private String idempotencyScope;

        /**
         * worker 必须回调的控制面接口。
         */
        private List<String> requiredCallbacks;
    }
}
