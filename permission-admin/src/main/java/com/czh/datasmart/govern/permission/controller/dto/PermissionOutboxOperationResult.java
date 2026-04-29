/**
 * @Author : Cui
 * @Date: 2026/04/27 00:40
 * @Description DataSmart Govern Backend - PermissionOutboxOperationResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

/**
 * outbox 人工操作结果。
 *
 * <p>动作类接口不要只返回 true/false。
 * 管理后台、运维脚本和审计人员通常需要知道：操作的是哪条事件、事件现在是什么状态、系统为什么这样处理。
 *
 * @param eventId outbox 事件业务 ID。
 * @param status 操作后的事件状态。
 * @param message 面向管理员的处理说明。
 */
public record PermissionOutboxOperationResult(
        String eventId,
        String status,
        String message
) {
}
