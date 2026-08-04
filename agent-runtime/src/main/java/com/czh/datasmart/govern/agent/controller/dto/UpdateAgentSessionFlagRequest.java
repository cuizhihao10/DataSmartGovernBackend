/**
 * @Author : Cui
 * @Date: 2026/08/04 00:00
 * @Description DataSmart Govern Backend - UpdateAgentSessionFlagRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 修改会话布尔标记的统一请求。
 *
 * <p>同一个 DTO 同时服务于“置顶/取消置顶”和“归档/恢复”接口，字段只有明确的目标状态，
 * 不采用 toggle 语义。这样客户端重试同一请求仍保持幂等，不会因为网络重试把状态再次翻转。</p>
 *
 * @param enabled 目标状态；必须显式传入 true 或 false，不能用 null 猜测用户意图
 */
public record UpdateAgentSessionFlagRequest(@NotNull(message = "enabled 不能为空") Boolean enabled) {
}
