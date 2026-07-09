/**
 * @Author : Cui
 * @Date: 2026/07/09 22:30
 * @Description DataSmart Govern Backend - SyncExecutionPolicy.java
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
 * 数据同步执行策略实体。
 *
 * <p>这张表解决的是“执行资源参数应该由谁配置”的问题。普通用户创建同步任务时只关心源端、目标端、
 * 对象映射、字段映射、where 条件和同步模式；channel、批大小、超时、重试、脏数据阈值、自动分片行数
 * 属于运行治理能力，应由管理员或运营在统一策略页维护。</p>
 *
 * <p>策略支持五种作用域：SYSTEM、PROJECT、CONNECTOR、DATASOURCE、TASK。运行时会按
 * 系统默认 -> 连接器/数据源 -> 项目 -> 任务级覆盖的顺序逐层合并，越靠后的策略优先级越高。</p>
 */
@Data
@TableName("data_sync_execution_policy")
public class SyncExecutionPolicy {

    /** 策略主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 租户 ID。0 表示平台全局默认策略，可被所有租户兜底使用。 */
    private Long tenantId;

    /** 项目 ID。PROJECT 或 TASK 作用域通常会携带该字段，用于项目级隔离和列表筛选。 */
    private Long projectId;

    /** 策略作用域：SYSTEM、PROJECT、CONNECTOR、DATASOURCE、TASK。 */
    private String scopeType;

    /**
     * 作用域稳定键。
     *
     * <p>例如 SYSTEM、PROJECT:101、CONNECTOR:SOURCE:MYSQL、DATASOURCE:23、TASK:1001。
     * 使用 scopeKey 是为了让前端、导入导出、审计和幂等更新都能引用同一个策略对象。</p>
     */
    private String scopeKey;

    /** 作用域展示名称，例如“FlashSync 项目策略”“MySQL 源端策略”。 */
    private String scopeName;

    /** 策略编码，同一个作用域下可用于区分默认策略、临时策略或客户现场专项策略。 */
    private String policyCode;

    /** 策略展示名称。 */
    private String policyName;

    /** 是否启用。禁用后策略保留审计痕迹，但不再参与运行时解析。 */
    private Boolean enabled;

    /** 数据源 ID。DATASOURCE 作用域使用，用于给某个源端或目标端连接单独限流。 */
    private Long datasourceId;

    /** 连接器类型，例如 MYSQL、POSTGRESQL。CONNECTOR 作用域使用。 */
    private String connectorType;

    /** 连接器方向：SOURCE 表示读取端，TARGET 表示写入端，ANY 表示两端都可匹配。 */
    private String connectorRole;

    /** 同步任务 ID。TASK 作用域使用，表示对某个任务做管理员覆盖。 */
    private Long syncTaskId;

    /** 自动 splitPk 的目标行数。系统用 rowCount / targetRowsPerShard 估算分片数。 */
    private Long targetRowsPerShard;

    /** 自动分片下限，防止小表被切得过碎。 */
    private Integer minShardCount;

    /** 自动分片上限，防止超大表生成过多分片导致账本和调度压力过高。 */
    private Integer maxShardCount;

    /** 最大并发 channel。真实执行会再与分片数、源端/目标端策略取安全下限。 */
    private Integer maxChannel;

    /** 每个 TaskGroup 容纳的分片数，用于把大量分片分批推进。 */
    private Integer taskGroupSize;

    /** 读取批大小，最终传递给 datasource-management Reader。 */
    private Integer readBatchSize;

    /** 写入批大小，最终传递给 datasource-management Writer。 */
    private Integer writeBatchSize;

    /** 提交间隔记录数，用于控制目标端事务提交粒度。 */
    private Integer commitIntervalRecords;

    /** 单次执行或批次的超时时间，单位秒。 */
    private Integer timeoutSeconds;

    /** 远端 run-once 最大重试次数。 */
    private Integer maxRetryCount;

    /** 脏数据数量阈值，超过后不应逐行 replay，应转失败分片重试或人工处理。 */
    private Long maxDirtyRecordCount;

    /** 脏数据比例阈值。 */
    private BigDecimal maxDirtyRecordRatio;

    /** 同一层级内的排序优先级，数值越大越靠后合并。 */
    private Integer priority;

    /** 策略说明，供管理员理解配置目的和适用边界。 */
    private String description;

    /** 创建人。 */
    private Long createdBy;

    /** 最近更新人。 */
    private Long updatedBy;

    /** 创建时间。 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间。 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
