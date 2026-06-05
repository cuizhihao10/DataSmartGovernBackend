/**
 * @Author : Cui
 * @Date: 2026/06/06 02:06
 * @Description DataSmart Govern Backend - AgentA2aPublicAgentCardView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;
import java.util.Map;

/**
 * A2A public Agent Card 只读响应。
 *
 * <p>A2A Agent Card 是外部 Agent 发现 DataSmart Master Agent 的公开名片。
 * 它应该描述 Agent 身份、通信接口、能力、Skill、输入输出模态和认证要求；但不能包含内部微服务地址、
 * token、secret、租户数据、记忆正文、工具实参或模型输出。</p>
 *
 * <p>当前响应是“公开发现草案”：字段形态尽量贴近 A2A AgentCard，但执行入口仍是占位的公开路径。
 * 真正上线时，网关层需要把该路径映射到受控域名，补充签名、缓存、版本回滚和租户级可见性策略。</p>
 *
 * @param name Agent 名称
 * @param description Agent 能力说明
 * @param supportedInterfaces A2A 支持的接口列表，第一项为首选接口
 * @param provider Agent 提供方信息
 * @param version Agent Card 版本，不等同于 DataSmart 整体产品版本
 * @param documentationUrl 公开文档地址或占位地址，不包含内部主机名
 * @param capabilities A2A capability 声明
 * @param securitySchemes 认证方案，使用 OpenAPI/A2A 风格结构描述，不包含凭证值
 * @param securityRequirements 联系该 Agent 时需要满足的安全要求
 * @param defaultInputModes 默认输入 MIME 类型
 * @param defaultOutputModes 默认输出 MIME 类型
 * @param skills 可公开发现的 READY Skill 列表
 * @param signatures Agent Card 签名。当前为空，后续生产化应接入 JWS 与密钥轮换
 * @param iconUrl Agent 图标 URL。当前允许为空，避免硬编码未治理的静态资源地址
 */
public record AgentA2aPublicAgentCardView(
        String name,
        String description,
        List<AgentA2aAgentInterfaceView> supportedInterfaces,
        AgentA2aAgentProviderView provider,
        String version,
        String documentationUrl,
        AgentA2aAgentCapabilitiesView capabilities,
        Map<String, Object> securitySchemes,
        List<Map<String, List<String>>> securityRequirements,
        List<String> defaultInputModes,
        List<String> defaultOutputModes,
        List<AgentA2aPublicSkillView> skills,
        List<Map<String, Object>> signatures,
        String iconUrl
) {
}
