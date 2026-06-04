/**
 * @Author : Cui
 * @Date: 2026/06/04 19:12
 * @Description DataSmart Govern Backend - AgentSkillPublicationManifestServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service;

import com.czh.datasmart.govern.agent.config.AgentRuntimeProperties;
import com.czh.datasmart.govern.agent.config.AgentSkillRegistryProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentSkillPublicationItemView;
import com.czh.datasmart.govern.agent.controller.dto.AgentSkillPublicationManifestView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent Skill 发布 Manifest 服务测试。
 *
 * <p>该测试保护的是“能力目录发布”契约，而不只是普通列表查询：
 * Python Runtime、智能网关、前端 Skill 市场、未来 MCP/A2A 适配层都会依赖 Manifest 判断哪些 Skill 可以展示、
 * 哪些 Skill 可以被模型规划、哪些 Skill 需要管理员先补审批/审计/隔离策略。</p>
 *
 * <p>为什么要单独测试 Manifest？
 * descriptor 测试只能证明单个 Skill 字段映射正确；Manifest 还多了目录级 contentFingerprint、
 * publicationState、过滤语义、运行时消费建议和协议兼容说明。任何一个字段漂移，都可能让运行时错误缓存旧能力，
 * 或让禁用 Skill 被模型误用，因此需要独立测试固定。</p>
 */
class AgentSkillPublicationManifestServiceTest {

    private AgentSkillPublicationManifestService service;

    @BeforeEach
    void setUp() {
        AgentRuntimeProperties runtimeProperties = new AgentRuntimeProperties();
        AgentSkillRegistryProperties skillProperties = new AgentSkillRegistryProperties();
        skillProperties.setSkillRegistry(new LinkedHashMap<>());
        skillProperties.getSkillRegistry().put("quality.rule.design", qualityRuleSkill());
        skillProperties.getSkillRegistry().put("governed.task.creation", taskCreationSkill());
        skillProperties.getSkillRegistry().put("disabled.skill", disabledSkill());

        AgentSkillRegistryService registryService = new AgentSkillRegistryService(runtimeProperties, skillProperties);
        service = new AgentSkillPublicationManifestService(registryService);
    }

    /**
     * 默认发布 Manifest 只暴露启用 Skill。
     *
     * <p>这是运行时视角的安全默认值：Python Runtime 读取 Manifest 后，通常会把 READY Skill 放进规划候选集。
     * 如果默认把禁用 Skill 也返回，模型可能在本地缓存或能力选择阶段误认为下线能力仍可使用。</p>
     */
    @Test
    void buildManifestShouldPublishOnlyEnabledSkillsByDefault() {
        AgentSkillPublicationManifestView manifest = service.buildManifest(false, null, null);

        assertEquals("datasmart.agent.skill.publication-manifest.v1", manifest.schemaVersion());
        assertEquals("DATASMART_AGENT_SKILL_PUBLICATION_MANIFEST", manifest.manifestType());
        assertEquals("MCP_STYLE_SKILL_MANIFEST", manifest.protocolHint());
        assertEquals("SNAPSHOT", manifest.publicationMode());
        assertEquals(2, manifest.skillCount());
        assertFalse(manifest.includeDisabled());
        assertEquals("ALL", manifest.domainFilter());
        assertEquals("ALL", manifest.riskLevelFilter());
        assertEquals(64, manifest.contentFingerprint().length());
        assertTrue(manifest.skills().stream().noneMatch(item -> item.skillCode().equals("disabled.skill")));
        assertTrue(manifest.consumerGuidance().stream().anyMatch(item -> item.contains("contentFingerprint")));
        assertTrue(manifest.compatibilityNotes().stream().anyMatch(item -> item.contains("MCP-style")));

        AgentSkillPublicationItemView qualitySkill = publicationItem(manifest, "quality.rule.design");
        assertEquals("READY", qualitySkill.publicationState());
        assertEquals(64, qualitySkill.contentFingerprint().length());
        assertTrue(qualitySkill.descriptorEndpoints().contains("/api/agent/skills/quality.rule.design/descriptor"));
    }

    /**
     * 高风险 Skill 如果已经声明人工审批、审计和隔离，也可以进入 READY。
     *
     * <p>这里固定一个很重要的产品判断：高风险不等于永远不能发布。
     * 商业化系统需要允许“高风险但受控”的能力上线，例如创建治理任务、发起同步任务、执行修复动作。
     * 真正需要阻断的是缺审批、缺审计、缺隔离，而不是风险等级本身。</p>
     */
    @Test
    void highRiskSkillShouldBeReadyWhenGovernancePolicyIsComplete() {
        AgentSkillPublicationManifestView manifest = service.buildManifest(false, "task-management", "high");

        assertEquals(1, manifest.skillCount());
        assertEquals("TASK_MANAGEMENT", manifest.domainFilter());
        assertEquals("HIGH", manifest.riskLevelFilter());

        AgentSkillPublicationItemView taskSkill = manifest.skills().getFirst();
        assertEquals("governed.task.creation", taskSkill.skillCode());
        assertEquals("HIGH", taskSkill.riskLevel());
        assertEquals("HUMAN_APPROVAL_REQUIRED", taskSkill.approvalPolicy());
        assertEquals("READY", taskSkill.publicationState());
    }

    /**
     * 管理员诊断或市场运营视角可以显式包含禁用 Skill。
     *
     * <p>`includeDisabled=true` 不能作为模型规划默认值，但对产品后台很有用：
     * 管理员需要知道某个能力是灰度下线、租户裁剪、策略缺口，还是根本没有被配置。
     * 因此 Manifest 同时支持运行时安全默认值和后台诊断视角。</p>
     */
    @Test
    void buildManifestShouldIncludeDisabledSkillsForMarketplaceDiagnostics() {
        AgentSkillPublicationManifestView manifest = service.buildManifest(true, null, null);

        assertEquals(3, manifest.skillCount());
        assertTrue(manifest.includeDisabled());
        assertTrue(manifest.consumerGuidance().stream().anyMatch(item -> item.contains("禁用 Skill")));

        AgentSkillPublicationItemView disabledSkill = publicationItem(manifest, "disabled.skill");
        assertEquals("DISABLED", disabledSkill.publicationState());
        assertFalse(disabledSkill.enabled());
        assertTrue(disabledSkill.publicationWarnings().stream().anyMatch(item -> item.contains("已禁用")));
    }

    /**
     * 同一份能力目录在内容未变化时应产生稳定指纹。
     *
     * <p>该断言保证 contentFingerprint 不包含 generatedAt 这类请求时间字段。
     * 运行时缓存、灰度对比和启动诊断都依赖这一点：如果每次请求指纹都变，Python Runtime 就无法判断远端目录是否真的变化。</p>
     */
    @Test
    void contentFingerprintShouldBeStableAcrossRequests() {
        AgentSkillPublicationManifestView first = service.buildManifest(false, null, null);
        AgentSkillPublicationManifestView second = service.buildManifest(false, null, null);

        assertEquals(first.contentFingerprint(), second.contentFingerprint());
        assertEquals(
                publicationItem(first, "quality.rule.design").contentFingerprint(),
                publicationItem(second, "quality.rule.design").contentFingerprint()
        );
    }

    private AgentSkillPublicationItemView publicationItem(AgentSkillPublicationManifestView manifest, String skillCode) {
        return manifest.skills().stream()
                .filter(item -> item.skillCode().equals(skillCode))
                .findFirst()
                .orElseThrow();
    }

    private AgentSkillRegistryProperties.SkillDefinitionProperties qualityRuleSkill() {
        AgentSkillRegistryProperties.SkillDefinitionProperties skill =
                new AgentSkillRegistryProperties.SkillDefinitionProperties();
        skill.setSkillCode("quality.rule.design");
        skill.setDisplayName("质量规则设计 Skill");
        skill.setDescription("根据元数据和历史异常生成质量规则草案");
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
        skill.setDescription("把 Agent 规划结果转换为需要审批的治理任务草案");
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
        skill.setDisplayName("已禁用演示 Skill");
        skill.setEnabled(false);
        return skill;
    }
}
