/**
 * @Author : Cui
 * @Date: 2026/06/06 01:19
 * @Description DataSmart Govern Backend - AgentA2aAgentCardPreviewView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;
import java.util.Map;

/**
 * A2A Agent Card 映射预览。
 *
 * <p>A2A 的 Agent Card 是外部 Agent 发现 DataSmart Agent 能力的“名片”。它应该描述身份、
 * 能力、技能、服务入口和认证要求，但不应该泄露密钥、内部服务地址、租户数据、工具实参或实现细节。
 * 当前视图保持 preview 状态：它说明未来 DataSmart Master Agent 可以怎样发布 Agent Card，
 * 但不会真的在 `/.well-known/agent-card.json` 暴露生产级卡片。</p>
 *
 * @param protocolVersion 当前参考的 A2A 协议版本
 * @param cardPath 未来 Agent Card 推荐发现路径。当前只是建议路径，不代表真实 endpoint 已启用
 * @param name Agent 名称
 * @param description Agent 能力说明，用于外部 Agent 判断是否适合委派数据治理任务
 * @param urlPreview 未来 A2A 服务入口草案。当前使用相对路径，避免暴露真实域名或内部服务地址
 * @param preferredTransport 首选传输方式草案
 * @param supportedTransports 支持传输方式草案。当前不表示所有传输已实现
 * @param capabilities A2A capability 摘要，例如 streaming、pushNotifications、extendedAgentCard
 * @param defaultInputModes 默认输入模态
 * @param defaultOutputModes 默认输出模态
 * @param securitySchemes 安全方案摘要，只描述认证类型，不包含 token、secret 或具体密钥位置
 * @param skillCount Agent Card 中暴露的 READY Skill 数量
 * @param skills Skill 能力列表，只包含低敏能力元数据
 * @param cardDisclosurePolicy Agent Card 暴露策略，说明当前不暴露内部状态、记忆正文和工具执行细节
 * @param signingRecommendation 对 Agent Card 签名、可信发现和密钥轮换的后续建议
 */
public record AgentA2aAgentCardPreviewView(
        String protocolVersion,
        String cardPath,
        String name,
        String description,
        String urlPreview,
        String preferredTransport,
        List<String> supportedTransports,
        Map<String, Boolean> capabilities,
        List<String> defaultInputModes,
        List<String> defaultOutputModes,
        List<String> securitySchemes,
        int skillCount,
        List<AgentA2aSkillPreviewView> skills,
        String cardDisclosurePolicy,
        String signingRecommendation
) {
}
