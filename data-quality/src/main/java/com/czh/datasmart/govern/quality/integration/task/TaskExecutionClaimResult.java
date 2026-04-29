/**
 * @Author : Cui
 * @Date: 2026/04/28 19:46
 * @Description DataSmart Govern Backend - TaskExecutionClaimResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.integration.task;

/**
 * task-management 任务认领结果的本地响应模型。
 *
 * <p>认领不到任务不是异常，而是正常的空队列状态。
 * 因此 claimed=false 时 task 和 executionRun 可以为空，执行器应短暂退避后再尝试认领。
 *
 * @param claimed 是否认领成功。
 * @param message 认领结果说明。
 * @param task 被认领的任务快照。
 * @param executionRun 本次任务执行记录。
 */
public record TaskExecutionClaimResult(
        boolean claimed,
        String message,
        TaskResponse task,
        TaskExecutionRunResponse executionRun
) {
}
