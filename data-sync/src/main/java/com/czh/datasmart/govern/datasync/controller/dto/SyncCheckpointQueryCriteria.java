/**
 * @Author : Cui
 * @Date: 2026/05/07 21:39
 * @Description DataSmart Govern Backend - SyncCheckpointQueryCriteria.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

/**
 * 同步 checkpoint 查询条件。
 */
public record SyncCheckpointQueryCriteria(
        Long syncTaskId,
        Long executionId,
        String checkpointType,
        Long current,
        Long size
) {
}
