/**
 * @Author : Cui
 * @Date: 2026/06/29 12:37
 * @Description DataSmart Govern Backend - DatasourceRunOnceRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.integration.datasource.runonce;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * datasource-management run-once 内部请求镜像。
 *
 * <p>这是 data-sync 侧对 datasource-management {@code /internal/sync-batch-runs/run-once} JSON 契约的本地镜像。
 * 两个微服务之间通过 HTTP JSON 交互，不通过 Java 编译依赖共享 DTO。这样可以保持服务独立发布，
 * 也能让契约演进更接近真实商用微服务架构。</p>
 *
 * <p>安全边界：</p>
 * <p>该请求可能包含字段清单、对象定位和 checkpoint 起点等内部执行事实。它只能发往 datasource-management internal 路由，
 * 不允许进入普通 controller 响应、runtime event、审计投影或日志正文。本类不使用 Lombok {@code @Data}，
 * 避免默认 {@code toString()} 打印敏感执行上下文。</p>
 */
@Getter
@Setter
public class DatasourceRunOnceRequest {

    private ExecutionPlan executionPlan;
    private List<String> selectedColumns;
    private List<String> writeColumns;
    private List<String> primaryKeyColumns;
    private Long actorId;
    private String actorRole;
    private Long actorTenantId;
    private String shardOrPartition;
    private Object checkpointValue;
    private Long previousRecordsRead;
    private Long previousRecordsWritten;
    private Long previousFailedRecordCount;

    /**
     * 批处理执行计划镜像。
     */
    @Getter
    @Setter
    public static class ExecutionPlan {
        private String planVersion;
        private String executionBoundary;
        private Long taskId;
        private Long executionId;
        private ReadPlan readPlan;
        private WritePlan writePlan;
        private CheckpointPlan checkpointPlan;
        private RuntimeControlPlan runtimeControlPlan;
        private List<String> warnings;
        private LocalDateTime generatedAt;
    }

    /**
     * 读取计划镜像。
     */
    @Getter
    @Setter
    public static class ReadPlan {
        private String connectorType;
        private Long datasourceId;
        private String objectLocator;
        private String readStrategy;
        private String syncMode;
        private String incrementalField;
        private List<ReadFilterCondition> filterConditions;
        private String customSql;
        private String customSqlFingerprint;
        private Boolean partitionConfigured;
        private Integer recommendedFetchSize;
        private List<String> requiredWorkerCapabilities;
    }

    /**
     * 受控读取过滤条件镜像。
     *
     * <p>该对象只通过 data-sync -> datasource-management 的 internal run-once 请求传输。
     * value 可能包含业务范围信息，因此不能打印请求体，也不能把该对象返回给普通 API。</p>
     */
    @Getter
    @Setter
    public static class ReadFilterCondition {
        private String column;
        private String operator;
        private Object value;
        private Boolean valueRequired;
    }

    /**
     * 写入计划镜像。
     */
    @Getter
    @Setter
    public static class WritePlan {
        private String connectorType;
        private Long datasourceId;
        private String objectLocator;
        private String writeStrategy;
        private String conflictPolicy;
        private Boolean primaryKeyRequired;
        private String primaryKeyField;
        private Integer recommendedWriteBatchSize;
        private Integer recommendedCommitIntervalRecords;
        private List<String> requiredWorkerCapabilities;
    }

    /**
     * checkpoint 计划镜像。
     */
    @Getter
    @Setter
    public static class CheckpointPlan {
        private String checkpointType;
        private String initialCheckpointPolicy;
        private Boolean resumeRequired;
        private Boolean shardAware;
        private Integer persistEveryRecords;
        private String checkpointValueVisibility;
    }

    /**
     * 运行控制计划镜像。
     */
    @Getter
    @Setter
    public static class RuntimeControlPlan {
        private String executorId;
        private LocalDateTime leaseExpireAt;
        private Boolean heartbeatRequired;
        private Integer timeoutSeconds;
        private Integer maxRetryCount;
        private Long maxDirtyRecordCount;
        private Double maxDirtyRecordRatio;
        private String idempotencyScope;
        private List<String> requiredCallbacks;
    }
}
