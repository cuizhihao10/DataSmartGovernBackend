/**
 * @Author : Cui
 * @Date: 2026/05/07 21:38
 * @Description DataSmart Govern Backend - SyncCheckpoint.java
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
 * 同步 checkpoint 实体。
 *
 * <p>checkpoint 表达“同步已经安全推进到哪里”。
 * 对增量同步、CDC、分片批同步、失败恢复和回放来说，checkpoint 是可靠性的核心，而不是普通日志字段。
 */
@Data
@TableName("data_sync_checkpoint")
public class SyncCheckpoint {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;
    /** 项目 ID，冗余自任务，用于项目级断点恢复视图和后续项目级数据保留策略。 */
    private Long projectId;
    /** 工作空间 ID，冗余自任务，用于空间级运行证据筛选。 */
    private Long workspaceId;
    private Long syncTaskId;
    private Long executionId;

    /** checkpoint 类型，例如 TIME_FIELD、ID_RANGE、KAFKA_OFFSET、FILE_POSITION、PARTITION_WINDOW。 */
    private String checkpointType;

    /** checkpoint 值，例如时间戳、最大 ID、Kafka offset 或 JSON 结构。 */
    private String checkpointValue;

    /** 分片或分区标识，支持并行任务保存多条 checkpoint。 */
    private String shardOrPartition;

    private Long recordsRead;
    private Long recordsWritten;
    private LocalDateTime checkpointTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
