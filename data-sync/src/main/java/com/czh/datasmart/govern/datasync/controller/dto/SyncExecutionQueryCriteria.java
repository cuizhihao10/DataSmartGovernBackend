/**
 * @Author : Cui
 * @Date: 2026/05/07 21:39
 * @Description DataSmart Govern Backend - SyncExecutionQueryCriteria.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

/**
 * 同步执行记录查询条件。
 */
public record SyncExecutionQueryCriteria(
        Long syncTaskId,
        String executionState,
        String triggerType,
        Long current,
        Long size
) {
}
