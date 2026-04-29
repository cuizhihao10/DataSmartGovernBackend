/**
 * @Author : Cui
 * @Date: 2026/04/28 19:46
 * @Description DataSmart Govern Backend - TaskExecutionHeartbeatRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.integration.task;

import lombok.Data;

/**
 * 调用 task-management 执行器心跳接口的本地请求模型。
 *
 * <p>心跳是长任务可靠性的关键：它既表示执行器仍然存活，也持续刷新租约、进度和 checkpoint。
 * 对质量扫描来说，checkpoint 未来可以保存扫描到的分区、主键范围、offset 或文件行号，
 * 为失败恢复、断点续跑和问题排查提供基础。
 */
@Data
public class TaskExecutionHeartbeatRequest {

    /**
     * 执行器实例 ID。
     *
     * <p>必须与认领任务时传入的 executorId 一致，否则 task-management 会拒绝续租，
     * 防止其他执行器误续租或抢占不属于自己的运行记录。
     */
    private String executorId;

    /**
     * 当前进度，范围 0-100。
     */
    private Integer progress;

    /**
     * 检查点。
     *
     * <p>建议使用可读的 key=value 或 JSON 摘要，例如 `phase=VALIDATING_PAYLOAD`、
     * `table=ods_customer, scannedRows=10000`。
     */
    private String checkpoint;

    /**
     * 本次续租秒数。
     */
    private Long leaseSeconds;
}
