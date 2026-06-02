/**
 * @Author : Cui
 * @Date: 2026/06/02 19:10
 * @Description DataSmartGovernBackend - AgentSkillAdmissionPolicyServiceImplTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service.impl;

import com.czh.datasmart.govern.permission.controller.dto.AgentSkillAdmissionEvaluateRequest;
import com.czh.datasmart.govern.permission.controller.dto.AgentSkillAdmissionPolicyView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Agent Skill 准入策略服务测试。
 *
 * <p>这些用例保护的是商业化产品语义，而不是 Spring MVC 绑定：
 * - 满足权限和角色时允许；
 * - 缺少权限时拒绝；
 * - 租户关闭 Skill 时拒绝；
 * - 普通用户不能启用高风险 Skill；
 * - 极高风险 workspace 不允许高风险 Skill；
 * - 缺少权限事实时只能条件性通过。</p>
 */
class AgentSkillAdmissionPolicyServiceImplTest {

    private final AgentSkillAdmissionPolicyServiceImpl service = new AgentSkillAdmissionPolicyServiceImpl();

    /**
     * 项目负责人具备质量规则草稿权限时，应允许启用中风险质量规则 Skill。
     */
    @Test
    void projectOwnerWithPermissionShouldBeAllowed() {
        AgentSkillAdmissionEvaluateRequest request = request("quality.rule.design", "MEDIUM", "PROJECT_OWNER");
        request.setRequiredPermissions(List.of("quality:rule:draft"));
        request.setGrantedPermissions(List.of("quality:rule:draft", "datasource:metadata:read"));

        AgentSkillAdmissionPolicyView view = service.evaluate(request);

        assertThat(view.getAllowed()).isTrue();
        assertThat(view.getAdmissionStatus()).isEqualTo("ALLOWED");
        assertThat(view.getMatchedPolicy()).isEqualTo("ALLOW_BY_BASELINE_RULE");
        assertThat(view.getSatisfiedPermissionsSummary()).containsExactly("quality:rule:draft");
        assertThat(view.getMissingPermissionsSummary()).isEmpty();
        assertThat(view.getPolicyVersion()).contains("QUALITY.RULE.DESIGN");
    }

    /**
     * 显式缺少必需权限时必须拒绝，避免 Skill 语义命中绕过权限边界。
     */
    @Test
    void missingRequiredPermissionShouldBeDenied() {
        AgentSkillAdmissionEvaluateRequest request = request("quality.rule.design", "MEDIUM", "PROJECT_OWNER");
        request.setRequiredPermissions(List.of("quality:rule:draft", "datasource:metadata:read"));
        request.setGrantedPermissions(List.of("datasource:metadata:read"));

        AgentSkillAdmissionPolicyView view = service.evaluate(request);

        assertThat(view.getAllowed()).isFalse();
        assertThat(view.getAdmissionStatus()).isEqualTo("DENIED_MISSING_PERMISSION");
        assertThat(view.getMatchedPolicy()).isEqualTo("MISSING_REQUIRED_PERMISSION");
        assertThat(view.getMissingPermissionsSummary()).containsExactly("quality:rule:draft");
        assertThat(view.getRejectionReason()).contains("quality:rule:draft");
    }

    /**
     * 租户级 Skill 开关关闭时，即使权限满足也必须拒绝。
     */
    @Test
    void disabledTenantSkillShouldBeDenied() {
        AgentSkillAdmissionEvaluateRequest request = request("quality.rule.design", "MEDIUM", "PROJECT_OWNER");
        request.setTenantSkillEnabled(false);
        request.setRequiredPermissions(List.of("quality:rule:draft"));
        request.setGrantedPermissions(List.of("quality:rule:draft"));

        AgentSkillAdmissionPolicyView view = service.evaluate(request);

        assertThat(view.getAllowed()).isFalse();
        assertThat(view.getAdmissionStatus()).isEqualTo("DENIED_TENANT_DISABLED");
        assertThat(view.getMatchedPolicy()).isEqualTo("TENANT_SKILL_DISABLED");
        assertThat(view.getRecommendedActions()).anyMatch(action -> action.contains("重新启用"));
    }

    /**
     * 普通用户即使拥有 task:create 权限，也不能直接启用高风险任务创建 Skill。
     */
    @Test
    void ordinaryUserShouldNotEnableHighRiskSkill() {
        AgentSkillAdmissionEvaluateRequest request = request("governed.task.creation", "HIGH", "ORDINARY_USER");
        request.setRequiredPermissions(List.of("task:create"));
        request.setGrantedPermissions(List.of("task:create"));

        AgentSkillAdmissionPolicyView view = service.evaluate(request);

        assertThat(view.getAllowed()).isFalse();
        assertThat(view.getAdmissionStatus()).isEqualTo("DENIED_RISK_ROLE");
        assertThat(view.getMatchedPolicy()).isEqualTo("HIGH_RISK_SKILL_ROLE_GUARD");
        assertThat(view.getRejectionReason()).contains("ORDINARY_USER");
    }

    /**
     * CRITICAL workspace 默认阻断 HIGH/CRITICAL Skill，防止高敏空间自动启用高风险能力包。
     */
    @Test
    void criticalWorkspaceShouldBlockHighRiskSkill() {
        AgentSkillAdmissionEvaluateRequest request = request("governed.task.creation", "HIGH", "PROJECT_OWNER");
        request.setWorkspaceRiskLevel("CRITICAL");
        request.setRequiredPermissions(List.of("task:create"));
        request.setGrantedPermissions(List.of("task:create"));

        AgentSkillAdmissionPolicyView view = service.evaluate(request);

        assertThat(view.getAllowed()).isFalse();
        assertThat(view.getAdmissionStatus()).isEqualTo("DENIED_WORKSPACE_RISK");
        assertThat(view.getMatchedPolicy()).isEqualTo("CRITICAL_WORKSPACE_BLOCKS_HIGH_RISK_SKILL");
        assertThat(view.getRecommendedActions()).anyMatch(action -> action.contains("只读/草稿类 Skill"));
    }

    /**
     * 缺少权限事实时不能伪装成完全通过，只能条件性通过，提醒生产环境补齐可信事实。
     */
    @Test
    void missingPermissionFactsShouldBeConditional() {
        AgentSkillAdmissionEvaluateRequest request = request("datasource.profiling", "LOW", "ORDINARY_USER");

        AgentSkillAdmissionPolicyView view = service.evaluate(request);

        assertThat(view.getAllowed()).isTrue();
        assertThat(view.getAdmissionStatus()).isEqualTo("CONDITIONAL");
        assertThat(view.getMatchedPolicy()).isEqualTo("CONDITIONAL_MISSING_PERMISSION_FACTS");
        assertThat(view.getRejectionReason()).contains("可信权限事实");
    }

    private AgentSkillAdmissionEvaluateRequest request(String skillCode, String riskLevel, String actorRole) {
        AgentSkillAdmissionEvaluateRequest request = new AgentSkillAdmissionEvaluateRequest();
        request.setTenantId(10L);
        request.setProjectId("project-a");
        request.setWorkspaceKey("tenant:10:project:project-a");
        request.setSkillCode(skillCode);
        request.setRiskLevel(riskLevel);
        request.setActorRole(actorRole);
        request.setTenantSkillEnabled(true);
        request.setWorkspaceRiskLevel("NORMAL");
        request.setTenantPlanCode("STANDARD");
        return request;
    }
}
