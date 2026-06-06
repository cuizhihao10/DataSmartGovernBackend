/**
 * @Author : Cui
 * @Date: 2026/06/06 12:55
 * @Description DataSmart Govern Backend - AgentA2aTaskEventPayloadFieldView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * A2A Task runtime event 字段白名单视图。
 *
 * <p>企业级 Agent 事件不能让业务代码随意写 Map。字段白名单的作用是让事件发布方、投影服务、前端 timeline、
 * push 适配器和审计台都知道哪些字段可以出现、字段敏感级别是什么、哪些消费者可以读取。这样做牺牲了一点编码自由度，
 * 但能显著降低敏感上下文扩散、字段漂移和前端脆弱耦合。</p>
 *
 * @param fieldName 字段名，使用稳定 camelCase
 * @param valueType 字段值类型，例如 string、boolean、integer、array 或 object-summary
 * @param sensitivity 敏感级别：PUBLIC_PROTOCOL、LOW_SENSITIVE、INTERNAL_SUMMARY、HASH_ONLY
 * @param required 是否每个 task event 都必须携带该字段
 * @param description 字段业务含义
 * @param allowedConsumers 允许读取该字段的消费方
 * @param examples 低敏示例。示例只能是枚举或占位，不能包含真实客户数据
 */
public record AgentA2aTaskEventPayloadFieldView(
        String fieldName,
        String valueType,
        String sensitivity,
        boolean required,
        String description,
        List<String> allowedConsumers,
        List<String> examples
) {
}
