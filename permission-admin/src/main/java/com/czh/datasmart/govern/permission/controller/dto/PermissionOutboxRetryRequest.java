/**
 * @Author : Cui
 * @Date: 2026/04/27 00:40
 * @Description DataSmart Govern Backend - PermissionOutboxRetryRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * outbox 人工重试请求。
 *
 * <p>系统会自动重试 FAILED 事件，但 DEAD 事件代表已经超过最大尝试次数。
 * 当 Kafka、网络或配置问题修复后，运营人员或平台管理员需要一个受控入口把 DEAD 事件重新放回待发送队列。
 */
@Data
public class PermissionOutboxRetryRequest {

    /**
     * 重试原因。
     *
     * <p>原因不是技术必需字段，但对商业系统很重要：
     * 它能解释为什么管理员在某个时间点重新投递事件，帮助后续审计和事故复盘。
     */
    @Size(max = 500, message = "重试原因不能超过 500 个字符")
    private String reason;
}
