/**
 * @Author : Cui
 * @Date: 2026/05/08 21:52
 * @Description DataSmart Govern Backend - SyncExecutionClaimResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;

/**
 * 同步执行认领结果。
 *
 * @param claimed 是否认领成功
 * @param message 结果说明
 * @param execution 被认领的执行记录
 * @param task 对应同步任务定义
 */
public record SyncExecutionClaimResult(
        boolean claimed,
        String message,
        SyncExecution execution,
        SyncTask task
) {
}
