/**
 * @Author : Cui
 * @Date: 2026/06/06 02:06
 * @Description DataSmart Govern Backend - AgentA2aAgentCapabilitiesView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;
import java.util.Map;

/**
 * A2A AgentCapabilities 视图。
 *
 * <p>能力声明必须保守。当前 DataSmart 还没有实现真实 A2A streaming、push notification 或 task endpoint，
 * 因此这些能力不能提前标 true；否则外部 Agent 会按照不存在的能力发起交互，造成集成失败或安全误判。</p>
 *
 * @param streaming 是否支持流式响应。当前 false
 * @param pushNotifications 是否支持推送通知。当前 false
 * @param extendedAgentCard 是否支持认证后扩展 Agent Card。当前作为后续方向标 true
 * @param extensions 协议扩展声明，当前只放低敏治理扩展说明
 */
public record AgentA2aAgentCapabilitiesView(
        Boolean streaming,
        Boolean pushNotifications,
        Boolean extendedAgentCard,
        List<Map<String, Object>> extensions
) {
}
