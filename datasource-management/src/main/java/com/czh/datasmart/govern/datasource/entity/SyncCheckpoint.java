package com.czh.datasmart.govern.datasource.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * @Author : Cui
 * @Date: 2026/4/18 23:12
 * @Description DataSmart Govern Backend - SyncCheckpoint.java
 * @Version:1.0.0
 *
 * 同步检查点实体。
 * 检查点是企业级同步产品和简单 demo 工具的重要分水岭。
 *
 * 没有检查点时，失败后通常只能整批重跑；
 * 有了检查点之后，平台才有机会支持：
 * - 断点续跑；
 * - 分片恢复；
 * - 精细化补数；
 * - 更短的恢复时间目标。
 *
 * 这里把检查点单独建表，而不是塞在任务表或执行表里，
 * 是为了支撑同一次执行下多个分区、多个游标和多种检查点类型并存。
 */
@Data
@TableName("sync_checkpoint")
public class SyncCheckpoint {

    /**
     * 检查点主键。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 所属执行记录 ID。
     */
    private Long executionId;

    /**
     * 检查点类型。
     * 例如时间水位、主键范围、Kafka offset、文件游标等。
     */
    private String checkpointType;

    /**
     * 检查点值。
     * 当前保留为文本字段，以兼容不同同步模式下差异较大的表达格式。
     */
    private String checkpointValue;

    /**
     * 分片或分区标识。
     * 并行任务下，这个字段有助于精确恢复某一个分片。
     */
    private String shardOrPartition;

    /**
     * 更新时间。
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
