package com.czh.datasmart.govern.datasource.controller.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * @Author : Cui
 * @Date: 2026/4/19 19:18
 * @Description DataSmart Govern Backend - SyncExecutorClaimResult.java
 * @Version:1.0.0
 *
 * 执行器认领结果。
 * 这个对象相当于控制面发给执行器的一份“最小执行上下文包”。
 *
 * 当前结果里既包含任务侧信息，也包含模板侧关键摘要，
 * 是为了让未来执行器在拿到结果后可以立即知道：
 * - 要执行哪一个任务；
 * - 这次对应哪次 execution；
 * - 租约到什么时候；
 * - 当前采用什么同步模式与写入策略；
 * - 源端和目标端对象分别是什么；
 * - 这次任务超时和重试边界是多少。
 */
@Data
public class SyncExecutorClaimResult {

    /**
     * 被认领的同步任务 ID。
     */
    private Long taskId;

    /**
     * 本次认领创建的执行记录 ID。
     * worker 后续心跳、进度、完成、失败和 checkpoint 回写都必须携带该 ID。
     */
    private Long executionId;

    /**
     * 任务所属租户。
     */
    private Long tenantId;

    /**
     * 任务绑定的同步模板 ID。
     */
    private Long templateId;

    /**
     * 成功认领任务的执行器实例标识。
     */
    private String executorId;

    /**
     * 任务名称。
     */
    private String taskName;

    /**
     * 任务当前状态。
     * 认领成功后通常为 RUNNING。
     */
    private String taskState;

    /**
     * 同步模式。
     */
    private String syncMode;

    /**
     * 目标端写入策略。
     */
    private String writeStrategy;

    /**
     * 执行模式。
     */
    private String runMode;

    /**
     * 触发类型。
     */
    private String triggerType;

    /**
     * 任务级超时时间。
     */
    private Integer timeoutSeconds;

    /**
     * 最大重试次数。
     */
    private Integer maxRetryCount;

    /**
     * 任务累计入队次数。
     */
    private Integer queueAttemptCount;

    /**
     * 源数据源 ID。
     */
    private Long sourceDatasourceId;

    /**
     * 源端 schema。
     */
    private String sourceSchemaName;

    /**
     * 源端对象名。
     */
    private String sourceObjectName;

    /**
     * 目标数据源 ID。
     */
    private Long targetDatasourceId;

    /**
     * 目标端 schema。
     */
    private String targetSchemaName;

    /**
     * 目标端对象名。
     */
    private String targetObjectName;

    /**
     * 租约过期时间。
     */
    private LocalDateTime leaseExpireAt;

    /**
     * 批处理执行计划。
     *
     * <p>该计划是本轮新增的执行器契约，描述 worker 应按什么方式读取、写入、保存 checkpoint 和回调控制面。
     * 它不包含 JDBC URL、用户名、密码、原始 SQL、样本数据或业务数据，只保留低敏控制字段。</p>
     */
    private SyncBatchExecutionPlan executionPlan;
}
