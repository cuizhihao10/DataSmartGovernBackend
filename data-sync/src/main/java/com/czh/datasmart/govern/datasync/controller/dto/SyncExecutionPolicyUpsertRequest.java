/**
 * @Author : Cui
 * @Date: 2026/07/09 22:35
 * @Description DataSmart Govern Backend - SyncExecutionPolicyUpsertRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 执行策略创建或更新请求。
 *
 * <p>该请求面向管理员或运营页面，不是普通新建同步任务向导的一部分。字段允许为空，含义是“不覆盖下层策略”；
 * 例如项目级策略只配置 maxChannel=6，则批大小、超时和脏数据阈值仍继承连接器或系统默认策略。</p>
 */
@Data
public class SyncExecutionPolicyUpsertRequest {

    /** 更新时传入策略 ID；创建时为空。 */
    private Long id;

    /** 租户 ID。平台管理员可显式创建 tenantId=0 的全局策略；普通租户管理员通常由 Header 决定。 */
    private Long tenantId;

    /** 项目 ID。PROJECT/TASK 作用域常用。 */
    private Long projectId;

    /** 作用域：SYSTEM、PROJECT、CONNECTOR、DATASOURCE、TASK。 */
    private String scopeType;

    /** 作用域稳定键；为空时服务端会根据作用域自动生成。 */
    private String scopeKey;

    /** 作用域展示名称。 */
    private String scopeName;

    /** 策略编码；为空时服务端按作用域生成默认编码。 */
    private String policyCode;

    /** 策略名称。 */
    private String policyName;

    /** 是否启用。为空时默认启用。 */
    private Boolean enabled;

    /** 数据源 ID。 */
    private Long datasourceId;

    /** 连接器类型。 */
    private String connectorType;

    /** 连接器方向：SOURCE、TARGET、ANY。 */
    private String connectorRole;

    /** 同步任务 ID。 */
    private Long syncTaskId;

    /** 自动分片目标行数。 */
    private Long targetRowsPerShard;

    /** 自动分片下限。 */
    private Integer minShardCount;

    /** 自动分片上限。 */
    private Integer maxShardCount;

    /** 最大 channel。 */
    private Integer maxChannel;

    /** TaskGroup 大小。 */
    private Integer taskGroupSize;

    /** 读取批大小。 */
    private Integer readBatchSize;

    /** 写入批大小。 */
    private Integer writeBatchSize;

    /** 提交间隔。 */
    private Integer commitIntervalRecords;

    /** 超时时间秒。 */
    private Integer timeoutSeconds;

    /** 最大重试次数。 */
    private Integer maxRetryCount;

    /** 脏数据数量阈值。 */
    private Long maxDirtyRecordCount;

    /** 脏数据比例阈值。 */
    private BigDecimal maxDirtyRecordRatio;

    /** 策略排序优先级。 */
    private Integer priority;

    /** 策略说明。 */
    private String description;
}
