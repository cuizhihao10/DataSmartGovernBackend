/**
 * @Author : Cui
 * @Date: 2026/06/04 19:12
 * @Description DataSmart Govern Backend - AgentSkillPublicationManifestServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service;

import com.czh.datasmart.govern.agent.config.AgentRuntimeProperties;
import com.czh.datasmart.govern.agent.config.AgentSkillRegistryProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentSkillPublicationDraftCreateRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentSkillPublicationItemView;
import com.czh.datasmart.govern.agent.controller.dto.AgentSkillPublicationLifecycleActionRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentSkillPublicationLifecycleView;
import com.czh.datasmart.govern.agent.controller.dto.AgentSkillPublicationManifestView;
import com.czh.datasmart.govern.agent.service.skill.AgentSkillPublicationLifecycleService;
import com.czh.datasmart.govern.agent.service.skill.InMemoryAgentSkillPublicationLifecycleStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent Skill 发布 Manifest 服务测试。
 *
 * <p>这组测试保护的不是普通列表查询，而是“运行时能力目录发布契约”：
 * Python Runtime、智能网关、Skill Marketplace、未来 MCP/A2A 适配层都会依赖 Manifest 判断哪些 Skill 可展示、
 * 哪些 Skill 可进入模型规划候选集、哪些 Skill 需要先补审计/审批/隔离策略。</p>
 *
 * <p>新增生命周期桥接后，Manifest 有两个来源：静态 registry 与发布生命周期 store。
 * 因此测试必须同时覆盖兼容性与安全性：老的无范围调用不能意外暴露租户级发布单；
 * scoped 调用只能看到本租户/项目内已经 READY 的 Skill；DRAFT/IN_REVIEW 不能被运行时消费。</p>
 */
class AgentSkillPublicationManifestServiceTest {

    private AgentSkillPublicationManifestService service;
    private AgentSkillPublicationLifecycleService lifecycleService;

    @BeforeEach
    void setUp() {
        AgentRuntimeProperties runtimeProperties = new AgentRuntimeProperties();
        AgentSkillRegistryProperties skillProperties = new AgentSkillRegistryProperties();
        skillProperties.setSkillRegistry(new LinkedHashMap<>());
        skillProperties.getSkillRegistry().put("quality.rule.design", qualityRuleSkill());
        skillProperties.getSkillRegistry().put("governed.task.creation", taskCreationSkill());
        skillProperties.getSkillRegistry().put("disabled.skill", disabledSkill());

        AgentSkillRegistryService registryService = new AgentSkillRegistryService(runtimeProperties, skillProperties);
        InMemoryAgentSkillPublicationLifecycleStore lifecycleStore =
                new InMemoryAgentSkillPublicationLifecycleStore();
        lifecycleService = new AgentSkillPublicationLifecycleService(runtimeProperties, lifecycleStore);
        service = new AgentSkillPublicationManifestService(registryService, Optional.of(lifecycleStore));
    }

    /**
     * 默认 Manifest 只暴露启用的静态 Skill。
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
     * <p>这里固定一个重要产品判断：高风险不等于永远不能发布。商业化系统需要允许“高风险但受控”的能力上线，
     * 例如创建治理任务、发起同步任务、执行修复动作。真正需要阻断的是缺审批、缺审计、缺隔离。</p>
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
     * 管理员诊断或市场运营视角可以显式包含禁用静态 Skill。
     *
     * <p>includeDisabled=true 不能作为模型规划默认值，但对产品后台很有用：
     * 管理员需要知道某个能力是灰度下线、租户裁剪、策略缺口，还是根本没有被配置。</p>
     */
    @Test
    void buildManifestShouldIncludeDisabledSkillsForMarketplaceDiagnostics() {
        AgentSkillPublicationManifestView manifest = service.buildManifest(true, null, null);

        assertEquals(3, manifest.skillCount());
        assertTrue(manifest.includeDisabled());
        assertTrue(manifest.consumerGuidance().stream().anyMatch(item -> item.contains("禁用")));

        AgentSkillPublicationItemView disabledSkill = publicationItem(manifest, "disabled.skill");
        assertEquals("DISABLED", disabledSkill.publicationState());
        assertFalse(disabledSkill.enabled());
        assertTrue(disabledSkill.publicationWarnings().stream().anyMatch(item -> item.contains("已禁用")));
    }

    /**
     * 同一份能力目录在内容未变化时应产生稳定指纹。
     *
     * <p>该断言保证 contentFingerprint 不包含 generatedAt 这类请求时间字段。
     * 运行时缓存、灰度对比和启动诊断都依赖这一点；如果每次请求指纹都变，Python Runtime 就无法判断目录是否真的变更。</p>
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

    /**
     * 审核通过的生命周期 Skill 会进入带租户/项目范围的 Manifest。
     *
     * <p>该测试保护“创建/审核/发布 -> 运行时发现”的闭环。如果 READY 发布单不能出现在 scoped Manifest 中，
     * 上一阶段新增的发布生命周期控制面就仍然停留在管理页孤岛，Python Runtime 实际无法消费。</p>
     */
    @Test
    void approvedLifecycleSkillShouldJoinScopedManifest() {
        AgentSkillPublicationLifecycleView ready = publishLifecycleSkill();

        AgentSkillPublicationManifestView manifest =
                service.buildManifest(false, "data-quality", "high", "tenant-a", "project-a");

        assertEquals(1, manifest.skillCount());
        AgentSkillPublicationItemView lifecycleSkill = publicationItem(manifest, "quality.rule.autofix");
        assertEquals("READY", lifecycleSkill.publicationState());
        assertEquals("HIGH", lifecycleSkill.riskLevel());
        assertEquals("HUMAN_APPROVAL_REQUIRED", lifecycleSkill.approvalPolicy());
        assertTrue(lifecycleSkill.descriptorEndpoints().contains(
                "/api/agent/skills/publications/" + ready.publicationId()));
        assertTrue(lifecycleSkill.publicationWarnings().stream().anyMatch(item -> item.contains("生命周期控制面")));
        assertTrue(manifest.consumerGuidance().stream().anyMatch(item -> item.contains("tenantId/projectId")));
        assertTrue(manifest.recommendedActions().stream().anyMatch(item -> item.contains("durable store")));
    }

    /**
     * 没有租户/项目范围时，即使发布单 READY，也不能出现在 Manifest。
     *
     * <p>这条规则比“能返回更多数据”更重要：Manifest 会进入运行时缓存，如果无范围请求能看到生命周期发布单，
     * 就可能让一个租户或项目的 Skill 被另一个运行时会话复用。</p>
     */
    @Test
    void lifecycleSkillShouldNotJoinManifestWithoutTenantProjectScope() {
        publishLifecycleSkill();

        AgentSkillPublicationManifestView manifest = service.buildManifest(false, "data-quality", "high");

        assertEquals(0, manifest.skillCount());
        assertTrue(manifest.consumerGuidance().stream().anyMatch(item -> item.contains("未携带完整")));
    }

    /**
     * DRAFT 与 IN_REVIEW 发布单不能被 Manifest 消费。
     *
     * <p>创建草稿只表示“低敏治理元数据已登记”；提交审核只表示“进入治理流程”。
     * 只有 READY 才表示通过发布资格审查，能进入运行时能力目录。</p>
     */
    @Test
    void lifecycleDraftAndInReviewSkillShouldNotJoinScopedManifest() {
        AgentSkillPublicationLifecycleView draft = lifecycleService.createDraft(
                validLifecycleDraft(),
                null,
                null,
                "creator-1"
        );

        AgentSkillPublicationManifestView draftManifest =
                service.buildManifest(false, "data-quality", "high", "tenant-a", "project-a");
        assertTrue(draftManifest.skills().stream()
                .noneMatch(item -> item.skillCode().equals("quality.rule.autofix")));

        lifecycleService.submitForReview(
                draft.publicationId(),
                new AgentSkillPublicationLifecycleActionRequest("creator-1", "治理元数据已补齐，提交审核", null, "INTERNAL"),
                null
        );
        AgentSkillPublicationManifestView inReviewManifest =
                service.buildManifest(false, "data-quality", "high", "tenant-a", "project-a");
        assertTrue(inReviewManifest.skills().stream()
                .noneMatch(item -> item.skillCode().equals("quality.rule.autofix")));
    }

    /**
     * 管理诊断场景可以通过 includeDisabled=true 查看已下线生命周期 Skill。
     *
     * <p>DEPRECATED 不应进入默认运行时规划，但它对审计和故障排查很重要：
     * 管理员需要知道某次运行为什么过去曾能看到某个 Skill，以及它何时被下线。</p>
     */
    @Test
    void deprecatedLifecycleSkillShouldJoinDiagnosticsManifestOnlyWhenIncludeDisabled() {
        AgentSkillPublicationLifecycleView ready = publishLifecycleSkill();
        lifecycleService.deprecate(
                ready.publicationId(),
                new AgentSkillPublicationLifecycleActionRequest("admin-1", "替换为新版本能力包，下线旧版本", null, null),
                null,
                "PLATFORM_ADMIN"
        );

        AgentSkillPublicationManifestView runtimeManifest =
                service.buildManifest(false, "data-quality", "high", "tenant-a", "project-a");
        assertEquals(0, runtimeManifest.skillCount());

        AgentSkillPublicationManifestView diagnosticsManifest =
                service.buildManifest(true, "data-quality", "high", "tenant-a", "project-a");
        AgentSkillPublicationItemView deprecatedSkill = publicationItem(diagnosticsManifest, "quality.rule.autofix");
        assertEquals("DEPRECATED", deprecatedSkill.publicationState());
        assertFalse(deprecatedSkill.enabled());
        assertTrue(deprecatedSkill.publicationWarnings().stream().anyMatch(item -> item.contains("已下线")));
    }

    private AgentSkillPublicationLifecycleView publishLifecycleSkill() {
        AgentSkillPublicationLifecycleView draft = lifecycleService.createDraft(
                validLifecycleDraft(),
                null,
                null,
                "creator-1"
        );
        lifecycleService.submitForReview(
                draft.publicationId(),
                new AgentSkillPublicationLifecycleActionRequest("creator-1", "治理元数据已补齐，提交审核", null, "INTERNAL"),
                null
        );
        return lifecycleService.approve(
                draft.publicationId(),
                new AgentSkillPublicationLifecycleActionRequest("admin-1", "审核通过，进入稳定发布渠道", "ticket-1001", "STABLE"),
                null,
                "TENANT_ADMIN"
        );
    }

    private AgentSkillPublicationItemView publicationItem(AgentSkillPublicationManifestView manifest, String skillCode) {
        return manifest.skills().stream()
                .filter(item -> item.skillCode().equals(skillCode))
                .findFirst()
                .orElseThrow();
    }

    private AgentSkillPublicationDraftCreateRequest validLifecycleDraft() {
        return new AgentSkillPublicationDraftCreateRequest(
                "quality.rule.autofix",
                "1.0.0",
                "质量规则自动修复 Skill",
                "根据质量报告生成受控修复计划草稿",
                "data-quality",
                "high",
                "human-approval-required",
                true,
                true,
                true,
                List.of("quality.rule.suggest", "quality.remediation.task.draft"),
                List.of("quality:rule:draft", "task:create"),
                List.of("semantic", "procedural"),
                "creator-1",
                "tenant-a",
                "project-a"
        );
    }

    private AgentSkillRegistryProperties.SkillDefinitionProperties qualityRuleSkill() {
        AgentSkillRegistryProperties.SkillDefinitionProperties skill =
                new AgentSkillRegistryProperties.SkillDefinitionProperties();
        skill.setSkillCode("quality.rule.design");
        skill.setDisplayName("质量规则设计 Skill");
        skill.setDescription("根据元数据和历史异常生成质量规则草稿");
        skill.setDomain("data-quality");
        skill.setRequiredTools(List.of("datasource.metadata.read", "quality.rule.suggest"));
        skill.setRequiredPermissions(List.of("quality:rule:draft"));
        skill.setMemoryDependencies(List.of("semantic", "episodic"));
        skill.setRiskLevel("medium");
        skill.setApprovalPolicy("draft-review");
        skill.setTriggerKeywords(List.of("质量", "规则"));
        skill.setExamples(List.of("生成客户主数据质量规划"));
        return skill;
    }

    private AgentSkillRegistryProperties.SkillDefinitionProperties taskCreationSkill() {
        AgentSkillRegistryProperties.SkillDefinitionProperties skill =
                new AgentSkillRegistryProperties.SkillDefinitionProperties();
        skill.setSkillCode("governed.task.creation");
        skill.setDisplayName("受控任务创建 Skill");
        skill.setDescription("把 Agent 规划结果转换为需要审批的治理任务草稿");
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
