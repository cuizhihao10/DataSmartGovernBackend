/**
 * @Author : Cui
 * @Date: 2026/05/08 22:02
 * @Description DataSmart Govern Backend - SyncExpiredLeaseRecoveryRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

/**
 * 过期租约恢复请求。
 *
 * <p>当前先作为运维接口请求体，后续可以被定时任务复用同一套服务方法。
 */
public record SyncExpiredLeaseRecoveryRequest(
        Long tenantId,
        Integer limit,
        Boolean requeue,
        String reason,
        String idempotencyKey
) {
}
