/**
 * @Author : Cui
 * @Date: 2026/06/30 23:21
 * @Description DataSmart Govern Backend - AgentSkillPublicationLifecycleServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.skill;

import com.czh.datasmart.govern.agent.config.AgentRuntimeProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentSkillPublicationDraftCreateRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentSkillPublicationLifecycleActionRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentSkillPublicationLifecycleQueryResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentSkillPublicationLifecycleView;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Skill 发布生命周期服务测试。
 *
 * <p>这组测试保护的是 Skill Marketplace 写侧闭环，而不是只读 Manifest：
 * 1. 草稿必须先被创建；</p>
 * <p>2. 发布前必须提交审核；</p>
 * <p>3. 审核通过后才能成为 READY；</p>
 * <p>4. READY 才允许下线；</p>
 * <p>5. 审批、审计、租户隔离、项目隔离和权限声明缺失时不能提交审核。</p>
 *
 * <p>这些规则的商业化意义是：Agent Skill 不能像普通配置一样随手上线。
 * Skill 会影响模型工具选择、记忆检索、审批策略和平台自动化动作，因此必须有明确状态机、操作者和审计理由。</p>
 */
class AgentSkillPublicationLifecycleServiceTest {

    private AgentSkillPublicationLifecycleService service;

    @BeforeEach
    void setUp() {
        service = new AgentSkillPublicationLifecycleService(
                new AgentRuntimeProperties(),
                new InMemoryAgentSkillPublicationLifecycleStore()
        );
    }

    /**
     * 验证完整发布生命周期：DRAFT -> IN_REVIEW -> READY -> DEPRECATED。
     */
    @Test
    void shouldCreateReviewApproveAndDeprecateSkillPublication() {
        AgentSkillPublicationLifecycleView draft = service.createDraft(
                validHighRiskDraft(),
                null,
                null,
                "creator-1"
        );

        assertEquals("DRAFT", draft.status());
        assertEquals("quality.rule.autofix", draft.skillCode());
        assertEquals("1.0.0", draft.version());
        assertEquals("DATA_QUALITY", draft.domain());
        assertEquals("HIGH", draft.riskLevel());
        assertEquals("HUMAN_APPROVAL_REQUIRED", draft.approvalPolicy());
        assertEquals(64, draft.contentFingerprint().length());
        assertEquals(List.of("SUBMIT_REVIEW"), draft.allowedNextActions());
        assertFalse(draft.policyIssues().contains("HUMAN_APPROVAL_REQUIRED_FOR_HIGH_RISK"));

        AgentSkillPublicationLifecycleView inReview = service.submitForReview(
                draft.publicationId(),
                new AgentSkillPublicationLifecycleActionRequest("creator-1", "治理元数据已补齐，提交平台审核", null, "INTERNAL"),
                null
        );
        assertEquals("IN_REVIEW", inReview.status());
        assertEquals(List.of("APPROVE", "REJECT"), inReview.allowedNextActions());
        assertEquals("creator-1", inReview.submittedBy());
        assertNotNull(inReview.submittedAt());

        AgentSkillPublicationLifecycleView ready = service.approve(
                draft.publicationId(),
                new AgentSkillPublicationLifecycleActionRequest("admin-1", "审批通过，先进入稳定发布渠道", "ticket-1001", "STABLE"),
                null,
                "TENANT_ADMIN"
        );
        assertEquals("READY", ready.status());
        assertEquals(List.of("DEPRECATE"), ready.allowedNextActions());
        assertEquals("admin-1", ready.reviewedBy());

        AgentSkillPublicationLifecycleQueryResponse readyQuery =
                service.query("tenant-a", "project-a", null, null, "ready", 20);
        assertEquals(1, readyQuery.totalMatched());
        assertEquals(1L, readyQuery.statusCounts().get("READY"));
        assertTrue(readyQuery.recommendedActions().stream().anyMatch(item -> item.contains("Manifest")));

        AgentSkillPublicationLifecycleView deprecated = service.deprecate(
                draft.publicationId(),
                new AgentSkillPublicationLifecycleActionRequest("admin-1", "替换为新版能力包，下线旧版本", null, null),
                null,
                "PLATFORM_ADMIN"
        );
        assertEquals("DEPRECATED", deprecated.status());
        assertTrue(deprecated.allowedNextActions().isEmpty());
        assertEquals("admin-1", deprecated.deprecatedBy());
        assertNotNull(deprecated.deprecatedAt());
    }

    /**
     * 发布前治理策略不完整时，不能进入审核。
     *
     * <p>这里同时覆盖三个高风险缺口：无审计、无项目隔离、无权限声明、高风险缺少人工审批。
     * 这些缺口如果放过，后续模型就可能选择一个无法追责、无法限权、无法人工确认的 Skill。</p>
     */
    @Test
    void shouldRejectSubmitWhenGovernancePolicyIsIncomplete() {
        AgentSkillPublicationLifecycleView draft = service.createDraft(
                new AgentSkillPublicationDraftCreateRequest(
                        "quality.rule.risky",
                        "0.1.0",
                        "高风险质量修复 Skill",
                        "用于生成质量修复计划的低敏说明",
                        "data-quality",
                        "high",
                        "none",
                        false,
                        true,
                        false,
                        List.of("quality.rule.suggest"),
                        List.of(),
                        List.of("procedural"),
                        "creator-1",
                        "tenant-a",
                        "project-a"
                ),
                null,
                null,
                null
        );

        PlatformBusinessException exception = assertThrows(
                PlatformBusinessException.class,
                () -> service.submitForReview(
                        draft.publicationId(),
                        new AgentSkillPublicationLifecycleActionRequest("creator-1", "尝试提交", null, null),
                        null
                )
        );
        assertTrue(exception.getMessage().contains("AUDIT_POLICY_MISSING"));
        assertTrue(exception.getMessage().contains("PROJECT_SCOPE_MISSING"));
        assertTrue(exception.getMessage().contains("REQUIRED_PERMISSION_MISSING"));
        assertTrue(exception.getMessage().contains("HUMAN_APPROVAL_REQUIRED_FOR_HIGH_RISK"));
    }

    /**
     * 同一租户项目内 skillCode + version 必须唯一。
     */
    @Test
    void shouldRejectDuplicateSkillCodeAndVersionInSameTenantProject() {
        service.createDraft(validHighRiskDraft(), null, null, "creator-1");

        PlatformBusinessException exception = assertThrows(
                PlatformBusinessException.class,
                () -> service.createDraft(validHighRiskDraft(), null, null, "creator-1")
        );
        assertTrue(exception.getMessage().contains("相同 skillCode/version"));
    }

    /**
     * 如果请求携带了非管理员角色，不能审核通过。
     *
     * <p>当前项目还没把 Keycloak/OIDC 与 permission-admin 审批动作打满，所以 service 只做轻量角色防线。
     * 这条测试避免后续有人把审核接口退化成“任何登录用户都能 approve”。</p>
     */
    @Test
    void shouldRejectApproveWhenActorRoleIsNotAdmin() {
        AgentSkillPublicationLifecycleView draft = service.createDraft(validHighRiskDraft(), null, null, "creator-1");
        service.submitForReview(
                draft.publicationId(),
                new AgentSkillPublicationLifecycleActionRequest("creator-1", "提交审核", null, null),
                null
        );

        PlatformBusinessException exception = assertThrows(
                PlatformBusinessException.class,
                () -> service.approve(
                        draft.publicationId(),
                        new AgentSkillPublicationLifecycleActionRequest("user-1", "普通用户尝试审批", null, null),
                        null,
                        "PROJECT_MEMBER"
                )
        );
        assertTrue(exception.getMessage().contains("无权执行操作"));
    }

    /**
     * 审核意见不能携带高敏内容。
     */
    @Test
    void shouldRejectSensitiveReviewComment() {
        AgentSkillPublicationLifecycleView draft = service.createDraft(validHighRiskDraft(), null, null, "creator-1");
        service.submitForReview(
                draft.publicationId(),
                new AgentSkillPublicationLifecycleActionRequest("creator-1", "提交审核", null, null),
                null
        );

        PlatformBusinessException exception = assertThrows(
                PlatformBusinessException.class,
                () -> service.approve(
                        draft.publicationId(),
                        new AgentSkillPublicationLifecycleActionRequest("admin-1", "包含 http://internal.example.local 的说明", null, null),
                        null,
                        "PLATFORM_ADMIN"
                )
        );
        assertTrue(exception.getMessage().contains("低敏摘要"));
    }

    private AgentSkillPublicationDraftCreateRequest validHighRiskDraft() {
        return new AgentSkillPublicationDraftCreateRequest(
                "quality.rule.autofix",
                "1.0.0",
                "质量规则自动修复 Skill",
                "根据质量报告生成受控修复计划草案",
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
}
