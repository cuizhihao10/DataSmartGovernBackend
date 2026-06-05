/**
 * @Author : Cui
 * @Date: 2026/06/06 01:19
 * @Description DataSmart Govern Backend - AgentExternalProtocolAdapterPreviewResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.time.Instant;
import java.util.List;

/**
 * MCP/A2A 外部协议适配预览响应。
 *
 * <p>这是 DataSmart Agent Runtime 面向“外部 Agent 互联”的第一层控制面契约。它不是完整 MCP Server，
 * 也不是完整 A2A Server，而是把当前内部能力目录投影为协议草案，帮助我们提前确定：
 * 哪些内部能力可以暴露、哪些必须隐藏、哪些需要审批、哪些只能作为资源目录、哪些适合进入 Agent Card。</p>
 *
 * <p>为什么要先做 preview：
 * Codex、Claude Code 等成熟 Agent Host 的关键能力之一，是把工具、上下文、技能、记忆、运行事件和模型路由
 * 组织成可治理的运行时，而不是让模型直接拼接 HTTP 请求。DataSmart 要接近这个方向，就必须先建立一个
 * 协议投影层，让 MCP/A2A 只看到“受控能力目录”，真实执行仍回到 Java Agent Runtime、Python AI Runtime、
 * permission-admin、task-management 和 runtime event 审计链路。</p>
 *
 * @param schemaVersion DataSmart 外部协议适配预览 schema 版本
 * @param generatedAt 预览生成时间
 * @param sourceManifestFingerprint 本次预览使用的 Skill Publication Manifest 指纹，便于排查协议目录是否过期
 * @param sourceSkillCount Skill Manifest 中的 Skill 数量
 * @param sourceToolCount 当前启用工具 descriptor 数量
 * @param policy 适配策略与安全边界
 * @param mcp MCP tools/resources/prompts 映射预览
 * @param a2a A2A Agent Card 映射预览
 * @param mappings 内部概念到外部协议概念的映射说明
 * @param referenceUrls 本次设计参考的协议文档 URL，便于后续学习和复核
 * @param productExpansionNotes 产品化扩展建议，提醒后续还需要考虑注册中心、签名、授权、追踪、限流等场景
 * @param recommendedNextSteps 下一步落地建议
 */
public record AgentExternalProtocolAdapterPreviewResponse(
        String schemaVersion,
        Instant generatedAt,
        String sourceManifestFingerprint,
        int sourceSkillCount,
        int sourceToolCount,
        AgentExternalProtocolAdapterPolicyView policy,
        AgentMcpAdapterPreviewView mcp,
        AgentA2aAgentCardPreviewView a2a,
        List<AgentExternalProtocolMappingView> mappings,
        List<String> referenceUrls,
        List<String> productExpansionNotes,
        List<String> recommendedNextSteps
) {
}
