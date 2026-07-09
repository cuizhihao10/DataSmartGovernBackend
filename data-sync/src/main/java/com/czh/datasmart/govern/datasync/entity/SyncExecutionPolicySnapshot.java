/**
 * @Author : Cui
 * @Date: 2026/07/09 22:31
 * @Description DataSmart Govern Backend - SyncExecutionPolicySnapshot.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 数据同步执行策略快照实体。
 *
 * <p>策略快照不是“当前策略配置”的副本，而是某次 execution 在运行当下真正使用的低敏解释。
 * 例如管理员今天把项目默认 channel 从 4 改成 8，昨天的 execution 仍然应该显示当时使用 channel=4，
 * 否则排查“昨天为什么慢”时会被当前配置误导。</p>
 *
 * <p>快照只保存治理参数，不保存 SQL、where 原文、连接串、密码、token、真实分片边界或样本行。</p>
 */
@Data
@TableName("data_sync_execution_policy_snapshot")
public class SyncExecutionPolicySnapshot {

    /** 快照主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 租户 ID。 */
    private Long tenantId;

    /** 项目 ID。 */
    private Long projectId;

    /** 同步任务 ID。 */
    private Long syncTaskId;

    /** 执行记录 ID。 */
    private Long executionId;

    /**
     * 命中的策略编码摘要，例如 SYSTEM_DEFAULT > DEFAULT_SOURCE_READ > DEFAULT_TARGET_WRITE > TASK_OVERRIDE。
     *
     * <p>这里刻意不再使用 MYSQL_SOURCE_DEFAULT/POSTGRESQL_TARGET_DEFAULT 这类示例编码作为注释样例，
     * 因为执行策略的基础层应该表达“通用读取/通用写入”，具体数据库类型只是在连接器策略中进一步覆盖的例外。</p>
     */
    private String policyCodeSummary;

    /** 策略解析顺序说明，当前固定为 TASK > PROJECT > DATASOURCE/CONNECTOR > SYSTEM。 */
    private String resolutionOrder;

    /** 自动分片目标行数。 */
    private Long targetRowsPerShard;

    /** 本次实际解析出的分片数。非分片任务可为空。 */
    private Integer resolvedShardCount;

    /** 本次实际生效的 channel。 */
    private Integer resolvedChannel;

    /** 本次实际生效的 TaskGroup 大小。 */
    private Integer taskGroupSize;

    /** 本次实际生效的读取批大小。 */
    private Integer readBatchSize;

    /** 本次实际生效的写入批大小。 */
    private Integer writeBatchSize;

    /** 本次实际生效的提交间隔。 */
    private Integer commitIntervalRecords;

    /** 本次实际生效的超时时间。 */
    private Integer timeoutSeconds;

    /** 本次实际生效的最大重试次数。 */
    private Integer maxRetryCount;

    /** 本次实际生效的脏数据数量阈值。 */
    private Long maxDirtyRecordCount;

    /** 本次实际生效的脏数据比例阈值。 */
    private BigDecimal maxDirtyRecordRatio;

    /** 低敏 JSON 快照，供前端运行详情展示和后续审计导出。 */
    private String snapshotJson;

    /** 载荷安全说明。 */
    private String payloadPolicy;

    /** 创建时间。 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间。 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
