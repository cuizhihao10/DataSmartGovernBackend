/**
 * @Author : Cui
 * @Date: 2026/08/21 10:00
 * @Description DataSmart Govern Backend - GraphFactApprovalRegisterResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

/**
 * 图事实审批登记响应。
 *
 * @param approvalFactId 服务端审批事实 ID
 * @param status 当前审批状态
 * @param factFingerprint 绑定的事实指纹
 * @param eventId APPROVED 时写入 outbox 的事件 ID，尚未批准时为空
 * @param message 人读说明
 */
public record GraphFactApprovalRegisterResponse(
        String approvalFactId,
        String status,
        String factFingerprint,
        String eventId,
        String message
) {
}
