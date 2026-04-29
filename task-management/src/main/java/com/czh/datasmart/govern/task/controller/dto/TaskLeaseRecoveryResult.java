package com.czh.datasmart.govern.task.controller.dto;

/**
 * @Author : Cui
 * @Date: 2026/04/27 01:10
 * @Description DataSmart Govern Backend - TaskLeaseRecoveryResult.java
 * @Version:1.0.0
 *
 * 租约超时恢复结果。
 *
 * @param scanned 扫描到的超时执行记录数量。
 * @param recovered 成功恢复的数量。
 * @param message 结果说明。
 */
public record TaskLeaseRecoveryResult(
        int scanned,
        int recovered,
        String message
) {
}
