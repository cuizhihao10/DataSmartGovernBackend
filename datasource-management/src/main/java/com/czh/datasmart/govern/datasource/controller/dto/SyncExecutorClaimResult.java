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

    private Long taskId;

    private Long executionId;

    private Long tenantId;

    private Long templateId;

    private String executorId;

    private String taskName;

    private String taskState;

    private String syncMode;

    private String writeStrategy;

    private String runMode;

    private String triggerType;

    private Integer timeoutSeconds;

    private Integer maxRetryCount;

    private Integer queueAttemptCount;

    private Long sourceDatasourceId;

    private String sourceSchemaName;

    private String sourceObjectName;

    private Long targetDatasourceId;

    private String targetSchemaName;

    private String targetObjectName;

    private LocalDateTime leaseExpireAt;
}
