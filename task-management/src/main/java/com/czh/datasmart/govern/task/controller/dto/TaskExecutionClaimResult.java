package com.czh.datasmart.govern.task.controller.dto;

import com.czh.datasmart.govern.task.entity.Task;
import com.czh.datasmart.govern.task.entity.TaskExecutionRun;

/**
 * @Author : Cui
 * @Date: 2026/04/27 01:10
 * @Description DataSmart Govern Backend - TaskExecutionClaimResult.java
 * @Version:1.0.0
 *
 * 执行器认领结果。
 *
 * <p>当没有可执行任务时，task 和 executionRun 为空，message 会说明原因。
 * 当认领成功时，同时返回任务快照和本次执行记录，执行器后续心跳应携带 runId。
 *
 * @param claimed 是否成功认领任务。
 * @param message 认领结果说明。
 * @param task 被认领的任务。
 * @param executionRun 本次执行记录。
 */
public record TaskExecutionClaimResult(
        boolean claimed,
        String message,
        Task task,
        TaskExecutionRun executionRun
) {
}
