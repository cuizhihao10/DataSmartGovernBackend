/**
 * @Author : Cui
 * @Date: 2026/05/07 21:39
 * @Description DataSmart Govern Backend - SyncErrorSampleQueryCriteria.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

/**
 * 同步错误样本查询条件。
 */
public record SyncErrorSampleQueryCriteria(
        Long syncTaskId,
        Long executionId,
        String errorType,
        Boolean retryable,
        Long current,
        Long size
) {
}
