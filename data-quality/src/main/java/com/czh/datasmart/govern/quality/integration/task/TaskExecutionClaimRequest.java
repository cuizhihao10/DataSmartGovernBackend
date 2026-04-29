/**
 * @Author : Cui
 * @Date: 2026/04/28 19:46
 * @Description DataSmart Govern Backend - TaskExecutionClaimRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.integration.task;

import lombok.Data;

/**
 * 调用 task-management 任务认领接口的本地请求模型。
 *
 * <p>这个类与 task-management 的 `TaskExecutionClaimRequest` 字段保持 JSON 兼容，
 * 但定义在 data-quality 内部，避免两个微服务在编译期互相依赖。
 *
 * <p>质量执行器未来会按 `taskType=DATA_QUALITY_SCAN` 认领任务。
 * 如果不指定 taskType，执行器可能领取其他业务模块任务，这在生产环境里是非常危险的，
 * 因此 data-quality 的调用方应优先使用 `TaskManagementIntegrationProperties.taskType`。
 */
@Data
public class TaskExecutionClaimRequest {

    /**
     * 执行器实例 ID。
     *
     * <p>建议使用稳定且可排障的值，例如 `data-quality-executor-local`、Pod 名称或机器 ID。
     * task-management 会把它写入 task.currentExecutorId 和 task_execution_run.executorId。
     */
    private String executorId;

    /**
     * 任务类型。
     *
     * <p>质量执行器应固定认领 `DATA_QUALITY_SCAN`，避免误消费数据同步、告警投递、AI 分析等其他任务。
     */
    private String taskType;

    /**
     * 本次认领租约秒数。
     *
     * <p>执行器需要在租约到期前持续调用 heartbeat 续租。
     * 租约太短会增加心跳压力，租约太长会导致执行器宕机后恢复变慢。
     */
    private Long leaseSeconds;
}
