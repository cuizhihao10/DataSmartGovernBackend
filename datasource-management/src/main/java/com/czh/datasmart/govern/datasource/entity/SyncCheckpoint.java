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
 * 检查点是企业级同步产品和 demo 工具的重要分水岭：
 * - 没有检查点，失败后通常只能整批重跑；
 * - 有检查点，才谈得上断点续跑、分区恢复、精细化补数和恢复 SLA。
 *
 * 这里把检查点单独建表，而不是直接塞进任务表或执行表，是为了支持：
 * 1. 一个执行过程产生多个分区检查点；
 * 2. 不同同步模式使用不同 checkpoint_type；
 * 3. 后续演进为更复杂的分片恢复逻辑。
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
     * 例如时间戳、水位线、主键范围、Kafka offset、文件游标等。
     */
    private String checkpointType;

    /**
     * 检查点值。
     * 保留文本字段，是为了容纳不同模式下结构差异较大的值。
     */
    private String checkpointValue;

    /**
     * 分片或分区标识。
     * 当任务采用并行分区处理时，这个字段能帮助精准恢复某一分片。
     */
    private String shardOrPartition;

    /**
     * 更新时间。
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
