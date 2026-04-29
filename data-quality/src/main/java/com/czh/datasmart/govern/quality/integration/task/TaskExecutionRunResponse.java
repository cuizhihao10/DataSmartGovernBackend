/**
 * @Author : Cui
 * @Date: 2026/04/28 19:46
 * @Description DataSmart Govern Backend - TaskExecutionRunResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.integration.task;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * task-management 单次任务执行记录的本地响应模型。
 *
 * <p>它用于描述“当前这次认领/运行”的状态，而不是任务主表的最终状态。
 * 同一个 task 可能因为失败重试产生多个 run，因此质量执行器在回调 data-quality 时应携带 runId，
 * 保证质量 execution 能精确关联到 task-management 的某一次运行。
 */
@Data
public class TaskExecutionRunResponse {

    /**
     * 执行记录 ID。
     */
    private Long id;

    /**
     * 所属任务 ID。
     */
    private Long taskId;

    /**
     * 同一任务下第几次执行。
     */
    private Long runNo;

    /**
     * 执行器实例 ID。
     */
    private String executorId;

    /**
     * 执行状态。
     */
    private String state;

    /**
     * 开始执行时间。
     */
    private LocalDateTime startedAt;

    /**
     * 最近一次心跳时间。
     */
    private LocalDateTime heartbeatAt;

    /**
     * 租约过期时间。
     */
    private LocalDateTime leaseExpireTime;

    /**
     * 执行进度。
     */
    private Integer progress;

    /**
     * 执行检查点。
     */
    private String checkpoint;

    /**
     * 错误摘要。
     */
    private String errorMessage;
}
