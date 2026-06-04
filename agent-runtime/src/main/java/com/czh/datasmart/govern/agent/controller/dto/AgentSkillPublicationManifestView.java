/**
 * @Author : Cui
 * @Date: 2026/06/04 18:35
 * @Description DataSmart Govern Backend - AgentSkillPublicationManifestView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.time.Instant;
import java.util.List;

/**
 * Agent Skill 发布 Manifest。
 *
 * <p>该对象是 Skill Marketplace 从“读侧列表/摘要”走向“可发布、可同步、可校验能力包”的第一版契约。
 * 它不是完整 MCP Server 响应，也不是数据库发布单；它是 DataSmart 内部的 MCP-style Skill Manifest：
 * Java 控制面作为能力事实源，Python Runtime、智能网关、前端市场页和未来 A2A/MCP 适配层可以读取同一份发布快照。</p>
 *
 * <p>为什么 Manifest 不直接等于 descriptors 列表？
 * descriptor 适合看单个 Skill 的完整字段；Manifest 更适合做“目录级发布”：
 * - 有目录级 contentFingerprint，可以判断整个可发布目录是否变化；
 * - 有 includeDisabled/domain/riskLevel 过滤条件，表达这次发布快照的边界；
 * - 有 consumerGuidance 和 compatibilityNotes，说明运行时应该如何消费、缓存和降级；
 * - 每个 item 都有 publicationState，便于市场页和运行时过滤不可发布能力。</p>
 *
 * @param schemaVersion Manifest schema 版本
 * @param manifestType Manifest 类型，当前固定为 DATASMART_AGENT_SKILL_PUBLICATION_MANIFEST
 * @param protocolHint 协议提示，当前是 MCP_STYLE_SKILL_MANIFEST，不表示已经实现完整 MCP JSON-RPC
 * @param descriptorSchemaVersion 该 Manifest 内 Skill descriptor 的版本
 * @param publicationMode 发布模式，当前为 SNAPSHOT，后续可扩展 DRAFT/RELEASE/CANARY
 * @param contentFingerprint 目录级内容指纹，不包含 generatedAt
 * @param generatedAt Manifest 生成时间
 * @param includeDisabled 是否包含禁用 Skill
 * @param domainFilter 本次发布快照的治理域过滤条件
 * @param riskLevelFilter 本次发布快照的风险等级过滤条件
 * @param skillCount 本次 Manifest 中的 Skill 数量
 * @param skills Manifest 条目列表
 * @param consumerGuidance 运行时消费建议
 * @param compatibilityNotes 与 MCP/A2A/Python Runtime 兼容边界说明
 * @param recommendedActions 后续产品化发布流建议
 */
public record AgentSkillPublicationManifestView(
        String schemaVersion,
        String manifestType,
        String protocolHint,
        String descriptorSchemaVersion,
        String publicationMode,
        String contentFingerprint,
        Instant generatedAt,
        Boolean includeDisabled,
        String domainFilter,
        String riskLevelFilter,
        int skillCount,
        List<AgentSkillPublicationItemView> skills,
        List<String> consumerGuidance,
        List<String> compatibilityNotes,
        List<String> recommendedActions
) {
}
