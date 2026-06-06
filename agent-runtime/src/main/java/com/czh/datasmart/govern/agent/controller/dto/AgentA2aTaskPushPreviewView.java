/**
 * @Author : Cui
 * @Date: 2026/06/06 13:03
 * @Description DataSmart Govern Backend - AgentA2aTaskPushPreviewView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * A2A Task push notification 预览。
 *
 * <p>push notification 不是简单的回调 URL 存储。商业化实现必须考虑 webhook 签名、重放保护、退避重试、
 * 最大失败次数、租户可见性、配置查询和删除。当前对象只展示这些边界，不返回真实 webhook 地址或认证材料。</p>
 *
 * @param pushEnabled 当前真实 push 是否启用
 * @param configManagementEnabled 当前 push config 管理接口是否启用
 * @param deliveryEligible 当前 task 场景是否适合发送低敏通知
 * @param pushPayloadPolicy push payload 低敏策略
 * @param lastDeliveryOutcome 最近一次投递结果摘要；preview 中为模拟摘要
 * @param webhookSecurityRequirements webhook 安全要求
 * @param retryPolicy 重试和失败处理策略
 * @param notes 补充说明
 */
public record AgentA2aTaskPushPreviewView(
        boolean pushEnabled,
        boolean configManagementEnabled,
        boolean deliveryEligible,
        String pushPayloadPolicy,
        String lastDeliveryOutcome,
        List<String> webhookSecurityRequirements,
        List<String> retryPolicy,
        List<String> notes
) {
}
