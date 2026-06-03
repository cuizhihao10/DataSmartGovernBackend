/**
 * @Author : Cui
 * @Date: 2026/05/23 21:39
 * @Description DataSmart Govern Backend - AgentSkillRegistryServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service;

import com.czh.datasmart.govern.agent.config.AgentRuntimeProperties;
import com.czh.datasmart.govern.agent.config.AgentSkillRegistryProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentSkillDescriptorView;
import com.czh.datasmart.govern.agent.controller.dto.AgentSkillMarketplaceFacetView;
import com.czh.datasmart.govern.agent.controller.dto.AgentSkillMarketplaceSummaryView;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent Skill 注册服务测试。
 *
 * <p>这组测试保护 Java Skill descriptor 和 Skill Marketplace 摘要的核心契约。
 * Python Runtime 会消费这些字段；如果 Java 侧字段名、过滤语义、默认值或市场统计口径发生变化，
 * 测试应第一时间暴露问题，而不是等到 Agent 规划结果异常才排查。
 */
class AgentSkillRegistryServiceTest {

    private AgentSkillRegistryService service;

    @BeforeEach
    void setUp() {
        AgentRuntimeProperties runtimeProperties = new AgentRuntimeProperties();
        AgentSkillRegistryProperties skillProperties = new AgentSkillRegistryProperties();
        skillProperties.setSkillRegistry(new LinkedHashMap<>());
        skillProperties.getSkillRegistry().put("quality.rule.design", qualityRuleSkill());
        skillProperties.getSkillRegistry().put("governed.task.creation", taskCreationSkill());
        skillProperties.getSkillRegistry().put("disabled.skill", disabledSkill());
        service = new AgentSkillRegistryService(runtimeProperties, skillProperties);
    }

    /**
     * 验证默认 enabledOnly 语义下只返回启用 Skill。
     */
    @Test
    void listSkillDescriptorsShouldOnlyReturnEnabledSkillsWhenRequested() {
        List<AgentSkillDescriptorView> descriptors = service.listSkillDescriptors(null, null, true);

        assertEquals(2, descriptors.size());
        assertTrue(descriptors.stream().noneMatch(item -> item.skillCode().equals("disabled.skill")));
    }

    /**
     * 验证按治理域和风险等级过滤。
     */
    @Test
    void listSkillDescriptorsShouldFilterByDomainAndRiskLevel() {
        List<AgentSkillDescriptorView> descriptors = service.listSkillDescriptors("task-management", "high", true);

        assertEquals(1, descriptors.size());
        assertEquals("governed.task.creation", descriptors.getFirst().skillCode());
        assertEquals("HUMAN_APPROVAL_REQUIRED", descriptors.getFirst().governance().approvalPolicy());
    }

    /**
     * 验证 Skill descriptor 暴露工具依赖、权限依赖、记忆依赖和治理策略。
     */
    @Test
    void getSkillDescriptorShouldExposeGovernanceAndMemoryContract() {
        AgentSkillDescriptorView descriptor = service.getSkillDescriptor("quality.rule.design");

        assertEquals("datasmart.agent.skill.v1", descriptor.schemaVersion());
        assertEquals("DATASMART_AGENT_SKILL", descriptor.descriptorType());
        assertEquals("AGENT_CARD_STYLE", descriptor.protocolHint());
        assertEquals("DATA_QUALITY", descriptor.domain());
        assertEquals(List.of("datasource.metadata.read", "quality.rule.suggest"), descriptor.requiredTools());
        assertEquals(List.of("quality:rule:draft"), descriptor.requiredPermissions());
        assertEquals("MEDIUM", descriptor.governance().riskLevel());
        assertEquals("DRAFT_REVIEW", descriptor.governance().approvalPolicy());
        assertTrue(descriptor.governance().tenantScoped());
        assertTrue(descriptor.governance().projectScoped());
        assertEquals(List.of("SEMANTIC", "EPISODIC"), descriptor.memory().memoryDependencies());
        assertEquals("PROJECT", descriptor.memory().defaultMemoryScope());
    }

    /**
     * 验证 Skill Marketplace 摘要会输出市场首页需要的数量、筛选维度和运营建议。
     *
     * <p>includeDisabled=true 是市场运营视角：禁用 Skill 不能被模型规划，但对前端市场页和管理员排障很重要，
     * 因为它代表灰度下线、租户裁剪、故障隔离或未来版本能力。
     */
    @Test
    void getMarketplaceSummaryShouldExposeGovernanceFacetsAndRecommendations() {
        AgentSkillMarketplaceSummaryView summary = service.getMarketplaceSummary(true);

        assertEquals("datasmart.agent.skill.marketplace.v1", summary.schemaVersion());
        assertEquals(3, summary.registrySkillCount());
        assertEquals(3, summary.visibleSkillCount());
        assertEquals(2, summary.enabledSkillCount());
        assertEquals(1, summary.disabledSkillCount());
        assertEquals(1, summary.highRiskSkillCount());
        assertEquals(2, summary.approvalRequiredSkillCount());
        assertEquals(3, summary.auditRequiredSkillCount());
        assertEquals(3, summary.tenantScopedSkillCount());
        assertEquals(3, summary.projectScopedSkillCount());

        AgentSkillMarketplaceFacetView qualityDomain = facet(summary.domainFacets(), "DATA_QUALITY");
        assertEquals("DOMAIN", qualityDomain.facetType());
        assertEquals(1, qualityDomain.totalCount());
        assertEquals(1, qualityDomain.enabledCount());

        AgentSkillMarketplaceFacetView disabledDomain = facet(summary.domainFacets(), "GENERAL_GOVERNANCE");
        assertEquals(1, disabledDomain.disabledCount());

        AgentSkillMarketplaceFacetView highRisk = facet(summary.riskLevelFacets(), "HIGH");
        assertEquals(1, highRisk.enabledCount());

        AgentSkillMarketplaceFacetView episodicMemory = facet(summary.memoryDependencyFacets(), "EPISODIC");
        assertEquals(2, episodicMemory.totalCount());

        assertTrue(summary.operationalWarnings().stream().anyMatch(item -> item.contains("已禁用 Skill")));
        assertTrue(summary.recommendedActions().stream().anyMatch(item -> item.contains("数据库市场")));
    }

    /**
     * 验证 includeDisabled=false 时摘要只面向可规划能力。
     *
     * <p>该语义适合 Python Runtime 或智能网关启动诊断：它们通常不关心已经禁用的 Skill，
     * 只需要判断“当前可用于模型规划的能力包集合”是否健康。
     */
    @Test
    void getMarketplaceSummaryShouldExcludeDisabledSkillsWhenRequested() {
        AgentSkillMarketplaceSummaryView summary = service.getMarketplaceSummary(false);

        assertEquals(3, summary.registrySkillCount());
        assertEquals(2, summary.visibleSkillCount());
        assertEquals(2, summary.enabledSkillCount());
        assertEquals(0, summary.disabledSkillCount());
        assertFalse(summary.domainFacets().stream().anyMatch(item -> item.value().equals("GENERAL_GOVERNANCE")));
        assertTrue(summary.operationalWarnings().stream().anyMatch(item -> item.contains("未发现明显配置缺口")));
    }

    /**
     * 验证未注册 Skill 会明确返回业务异常。
     */
    @Test
    void getSkillDescriptorShouldRejectUnknownSkill() {
        assertThrows(PlatformBusinessException.class, () -> service.getSkillDescriptor("unknown.skill"));
    }

    private AgentSkillMarketplaceFacetView facet(List<AgentSkillMarketplaceFacetView> facets, String value) {
        return facets.stream()
                .filter(item -> item.value().equals(value))
                .findFirst()
                .orElseThrow();
    }

    private AgentSkillRegistryProperties.SkillDefinitionProperties qualityRuleSkill() {
        AgentSkillRegistryProperties.SkillDefinitionProperties skill =
                new AgentSkillRegistryProperties.SkillDefinitionProperties();
        skill.setSkillCode("quality.rule.design");
        skill.setDisplayName("质量规则设计 Skill");
        skill.setDomain("data-quality");
        skill.setRequiredTools(List.of("datasource.metadata.read", "quality.rule.suggest"));
        skill.setRequiredPermissions(List.of("quality:rule:draft"));
        skill.setMemoryDependencies(List.of("semantic", "episodic"));
        skill.setRiskLevel("medium");
        skill.setApprovalPolicy("draft-review");
        skill.setTriggerKeywords(List.of("质量", "规则"));
        skill.setExamples(List.of("生成客户主数据质量规则"));
        return skill;
    }

    private AgentSkillRegistryProperties.SkillDefinitionProperties taskCreationSkill() {
        AgentSkillRegistryProperties.SkillDefinitionProperties skill =
                new AgentSkillRegistryProperties.SkillDefinitionProperties();
        skill.setSkillCode("governed.task.creation");
        skill.setDisplayName("受控任务创建 Skill");
        skill.setDomain("task-management");
        skill.setRequiredTools(List.of("task.create.draft"));
        skill.setRequiredPermissions(List.of("task:create"));
        skill.setMemoryDependencies(List.of("procedural", "episodic"));
        skill.setRiskLevel("high");
        skill.setApprovalPolicy("human-approval-required");
        skill.setDefaultMemoryScope("session");
        skill.setRetentionDays(7);
        return skill;
    }

    private AgentSkillRegistryProperties.SkillDefinitionProperties disabledSkill() {
        AgentSkillRegistryProperties.SkillDefinitionProperties skill =
                new AgentSkillRegistryProperties.SkillDefinitionProperties();
        skill.setSkillCode("disabled.skill");
        skill.setEnabled(false);
        return skill;
    }
}
