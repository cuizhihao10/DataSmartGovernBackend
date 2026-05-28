/**
 * @Author : Cui
 * @Date: 2026/05/23 21:38
 * @Description DataSmart Govern Backend - AgentSkillRegistryService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service;

import com.czh.datasmart.govern.agent.config.AgentRuntimeProperties;
import com.czh.datasmart.govern.agent.config.AgentSkillRegistryProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentSkillDescriptorView;
import com.czh.datasmart.govern.agent.controller.dto.AgentSkillGovernanceDescriptorView;
import com.czh.datasmart.govern.agent.controller.dto.AgentSkillMemoryDescriptorView;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Agent Skill 注册服务。
 *
 * <p>该服务把配置中的 Skill 注册表转换成稳定 descriptor。
 * 它不执行 Skill，也不做模型选择；它只负责提供“有哪些能力包、依赖什么工具、需要什么权限、
 * 会读写什么记忆、审批和审计边界是什么”的控制面事实。
 *
 * <p>为什么 Skill 要独立于工具目录：
 * 工具回答“能调用哪个平台动作”，Skill 回答“某类治理目标应该如何组织工具、记忆和审批策略”。
 * 如果把 Skill 直接塞进工具目录，后续质量规则设计、同步事故诊断、权限解释等能力会和底层 HTTP
 * 工具混在一起，难以做能力市场、租户开关和 Agent Card 映射。
 */
@Service
@RequiredArgsConstructor
public class AgentSkillRegistryService {

    /**
     * DataSmart Skill descriptor 版本。
     */
    private static final String SKILL_DESCRIPTOR_SCHEMA_VERSION = "datasmart.agent.skill.v1";

    private static final String SKILL_DESCRIPTOR_TYPE = "DATASMART_AGENT_SKILL";
    private static final String SKILL_DESCRIPTOR_PROTOCOL_HINT = "AGENT_CARD_STYLE";

    private final AgentRuntimeProperties runtimeProperties;
    private final AgentSkillRegistryProperties skillProperties;

    /**
     * 查询 Skill 描述符列表。
     *
     * <p>支持按治理域、风险等级和启用状态过滤。
     * 当前 Skill 数量较小，内存过滤足够；后续迁移数据库后再补分页、版本和租户维度。
     */
    public List<AgentSkillDescriptorView> listSkillDescriptors(String domain,
                                                               String riskLevel,
                                                               Boolean enabledOnly) {
        ensureRuntimeEnabled();
        return skillProperties.getSkillRegistry().entrySet().stream()
                .map(this::toDescriptor)
                .filter(item -> domain == null || domain.isBlank() || item.domain().equals(normalize(domain)))
                .filter(item -> riskLevel == null || riskLevel.isBlank()
                        || item.governance().riskLevel().equals(normalize(riskLevel)))
                .filter(item -> !Boolean.TRUE.equals(enabledOnly) || Boolean.TRUE.equals(item.governance().enabled()))
                .sorted(Comparator.comparing(AgentSkillDescriptorView::skillCode))
                .toList();
    }

    /**
     * 查询单个 Skill 描述符。
     *
     * <p>Python Runtime 可以在选择某个 Skill 后再次按 skillCode 拉取详情，
     * 确认所需工具、记忆依赖和审批策略是否仍然有效。
     */
    public AgentSkillDescriptorView getSkillDescriptor(String skillCode) {
        ensureRuntimeEnabled();
        AgentSkillRegistryProperties.SkillDefinitionProperties skill =
                skillProperties.getSkillRegistry().get(skillCode);
        if (skill == null) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND,
                    "Agent Skill 描述符不存在，skillCode=" + skillCode);
        }
        return toDescriptor(Map.entry(skillCode, skill));
    }

    private void ensureRuntimeEnabled() {
        if (!Boolean.TRUE.equals(runtimeProperties.getEnabled())) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT, "Agent Runtime 当前未启用");
        }
    }

    private AgentSkillDescriptorView toDescriptor(
            Map.Entry<String, AgentSkillRegistryProperties.SkillDefinitionProperties> entry) {
        AgentSkillRegistryProperties.SkillDefinitionProperties skill = entry.getValue();
        String resolvedCode = skill.getSkillCode() == null || skill.getSkillCode().isBlank()
                ? entry.getKey()
                : skill.getSkillCode();
        return new AgentSkillDescriptorView(
                SKILL_DESCRIPTOR_SCHEMA_VERSION,
                SKILL_DESCRIPTOR_TYPE,
                SKILL_DESCRIPTOR_PROTOCOL_HINT,
                resolvedCode,
                skill.getDisplayName(),
                skill.getDescription(),
                normalizeText(skill.getDomain(), "GENERAL_GOVERNANCE"),
                safeList(skill.getRequiredTools()),
                safeList(skill.getRequiredPermissions()),
                safeList(skill.getTriggerKeywords()),
                safeList(skill.getExamples()),
                new AgentSkillGovernanceDescriptorView(
                        skill.getEnabled(),
                        normalizeText(skill.getRiskLevel(), "LOW"),
                        normalizeText(skill.getApprovalPolicy(), "NONE"),
                        skill.getTenantScoped(),
                        skill.getProjectScoped(),
                        skill.getAuditRequired()
                ),
                new AgentSkillMemoryDescriptorView(
                        safeList(skill.getMemoryDependencies()).stream()
                                .map(value -> normalizeText(value, "SHORT_TERM"))
                                .toList(),
                        normalizeText(skill.getDefaultMemoryScope(), "PROJECT"),
                        skill.getRetentionDays()
                )
        );
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private String normalize(String value) {
        return value.trim().replace('-', '_').toUpperCase(Locale.ROOT);
    }

    private String normalizeText(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : normalize(value);
    }
}
