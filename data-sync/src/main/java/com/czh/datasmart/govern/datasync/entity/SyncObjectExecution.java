/**
 * @Author : Cui
 * @Date: 2026/07/06 21:49
 * @Description DataSmart Govern Backend - SyncObjectExecution.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 多对象同步的对象级执行记录实体。
 *
 * <p>这张表是 data-sync 从“父 execution 粗粒度状态”升级到“DataX-style 子任务/分片可恢复状态”的关键事实表。
 * 一个 {@link SyncExecution} 可以包含多条 {@code SyncObjectExecution}：</p>
 * <p>1. 当前阶段：一条记录通常表示 OBJECT_LIST 中的一张源表到目标表映射；</p>
 * <p>2. 后续阶段：同一张表可以继续拆成多个 splitPk、时间窗口、hash 分区或文件分片，本表仍可复用为分片级账本；</p>
 * <p>3. 运营价值：当父任务部分成功时，运维人员可以看到“哪些对象成功、哪些对象失败、失败尝试了几次”，
 * 而不是只看到一个模糊的 FAILED。</p>
 *
 * <p>敏感信息边界：本表是 data-sync 内部控制面事实表，可以保存对象名用于恢复和排障；但是普通 API、日志、指标和
 * 跨服务 receipt 仍然不应直接暴露对象名、字段映射正文、where 条件、SQL、连接串、凭据或样本行。</p>
 */
@Data
@TableName("data_sync_object_execution")
public class SyncObjectExecution {

    /**
     * 对象级执行记录主键。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 租户 ID，冗余自父 execution，用于租户级历史查询、清理和审计。
     */
    private Long tenantId;

    /**
     * 项目 ID，冗余自父 execution，用于项目级运行明细、失败率统计和权限过滤。
     */
    private Long projectId;

    /**
     * 工作空间 ID，冗余自父 execution，用于空间级运行证据和运营看板。
     */
    private Long workspaceId;

    /**
     * 同步任务 ID。
     */
    private Long syncTaskId;

    /**
     * 父级同步执行 ID。对象级记录一定隶属于某一次父 execution。
     */
    private Long executionId;

    /**
     * 同步模板 ID，便于从对象级明细反查当时使用的模板配置。
     */
    private Long templateId;

    /**
     * 对象在 objectMappingConfig.mappings 中的顺序号，从 0 开始。
     *
     * <p>该字段和 executionId 组成唯一约束，保证同一个父 execution 下同一个对象只会有一条执行账本。
     * 未来扩展到分片时，可以在保持 objectOrdinal 表达“第几张表”的同时新增 shardOrdinal 或 partitionKey。</p>
     */
    private Integer objectOrdinal;

    /**
     * 源端 schema 或命名空间。
     */
    private String sourceSchemaName;

    /**
     * 源端对象名，例如表、视图、topic 或文件逻辑对象。
     */
    private String sourceObjectName;

    /**
     * 目标端 schema 或命名空间。
     */
    private String targetSchemaName;

    /**
     * 目标端对象名。
     */
    private String targetObjectName;

    /**
     * 对象级执行状态，取值来自 {@code SyncObjectExecutionState}。
     */
    private String objectState;

    /**
     * 当前对象已经尝试执行的次数。
     *
     * <p>这里统计的是对象级尝试次数，不是父任务重试次数。成功对象不会因为另一个对象失败而增加尝试次数。</p>
     */
    private Integer attemptCount;

    /**
     * 当前对象允许的最大尝试次数。
     *
     * <p>默认由 data-sync 控制面解析模板 retryPolicy 得到；如果模板未声明，则采用保守默认值。
     * 将该值落表是为了让恢复时使用“创建当时的策略”，避免模板后来变更导致历史 execution 行为漂移。</p>
     */
    private Integer maxAttemptCount;

    /**
     * 当前对象累计读取记录数。
     */
    private Long recordsRead;

    /**
     * 当前对象累计写入记录数。
     */
    private Long recordsWritten;

    /**
     * 当前对象失败记录数或失败对象计数。
     */
    private Long failedRecordCount;

    /**
     * 最近一次失败类型，只保存低敏分类，例如 SOURCE_READ_ERROR、TARGET_WRITE_ERROR、BRIDGE_PLAN_BLOCKED。
     */
    private String lastErrorType;

    /**
     * 最近一次失败码，只保存低敏错误码，不保存 SQL、连接串、字段值或样本行。
     */
    private String lastErrorCode;

    /**
     * 最近一次失败摘要，长度受控，只用于排障提示。
     */
    private String lastErrorMessage;

    /**
     * 当前对象首次开始执行时间。
     */
    private LocalDateTime startedAt;

    /**
     * 当前对象终态时间，SUCCEEDED、FAILED 或 SKIPPED 时写入。
     */
    private LocalDateTime finishedAt;

    /**
     * 载荷安全策略说明。
     */
    private String payloadPolicy;

    /**
     * 记录创建时间。
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 记录更新时间。
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
