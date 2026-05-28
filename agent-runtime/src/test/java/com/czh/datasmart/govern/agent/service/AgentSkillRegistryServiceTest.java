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
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent Skill 注册服务测试。
 *
 * <p>这组测试保护 Java Skill descriptor 的核心契约。
 * Python Runtime 后续会消费这些字段，如果 Java 侧字段名、过滤语义或默认值悄悄变化，
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
     * 验证默认只返回启用 Skill。
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
     * 验证未注册 Skill 会明确返回业务异常。
     */
    @Test
    void getSkillDescriptorShouldRejectUnknownSkill() {
        assertThrows(PlatformBusinessException.class, () -> service.getSkillDescriptor("unknown.skill"));
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
