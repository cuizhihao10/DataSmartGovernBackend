/**
 * @Author : Cui
 * @Date: 2026/05/07 21:28
 * @Description DataSmart Govern Backend - SyncTaskOperationResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

/**
 * 同步任务动作结果。
 *
 * @param taskId 任务 ID
 * @param state 动作完成后的任务状态
 * @param message 面向调用方和运营人员的结果说明
 * @param executionId 本次动作创建的执行记录 ID；不创建 execution 的管理动作可以为空
 */
public record SyncTaskOperationResult(Long taskId, String state, String message, Long executionId) {

    /** 保留原三字段构造方式，避免普通生命周期动作被迫填充不存在的 executionId。 */
    public SyncTaskOperationResult(Long taskId, String state, String message) {
        this(taskId, state, message, null);
    }
}
