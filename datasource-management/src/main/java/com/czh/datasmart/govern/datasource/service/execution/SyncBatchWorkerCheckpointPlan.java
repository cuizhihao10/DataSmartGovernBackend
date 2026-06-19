/**
 * @Author : Cui
 * @Date: 2026/06/20 03:22
 * @Description DataSmart Govern Backend - SyncBatchWorkerCheckpointPlan.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.execution;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * worker checkpoint 执行计划。
 *
 * <p>该对象把控制面 checkpoint 语义整理成 worker 更容易消费的形式。
 * 它不包含真实 checkpoint 值，只描述类型、保存频率、是否需要恢复和可见性边界。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SyncBatchWorkerCheckpointPlan {

    /**
     * checkpoint 类型。
     */
    private String checkpointType;

    /**
     * 初始 checkpoint 策略。
     */
    private String initialCheckpointPolicy;

    /**
     * 是否要求断点续跑。
     */
    private Boolean resumeRequired;

    /**
     * 是否按分片/分区保存。
     */
    private Boolean shardAware;

    /**
     * 建议每处理多少条记录保存一次 checkpoint。
     */
    private Integer persistEveryRecords;

    /**
     * checkpoint 值可见性说明。
     */
    private String checkpointValueVisibility;
}
