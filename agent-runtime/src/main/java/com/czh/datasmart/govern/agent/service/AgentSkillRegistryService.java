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
import com.czh.datasmart.govern.agent.controller.dto.AgentSkillMarketplaceFacetView;
import com.czh.datasmart.govern.agent.controller.dto.AgentSkillMarketplaceSummaryView;
import com.czh.datasmart.govern.agent.controller.dto.AgentSkillMemoryDescriptorView;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Agent Skill 注册服务。
 *
 * <p>该服务把配置中的 Skill 注册表转换成稳定 descriptor。它不执行 Skill，也不做模型选择；
 * 它只负责提供“有哪些能力包、依赖什么工具、需要什么权限、会读写什么记忆、审批和审计边界是什么”
 * 这些控制面事实。
 *
 * <p>为什么 Skill 要独立于工具目录？
 * 工具回答“能调用哪个平台动作”，Skill 回答“某类治理目标应该如何组织工具、记忆和审批策略”。
 * 如果把 Skill 直接塞进工具目录，后续质量规则设计、同步事故诊断、权限解释等能力会和底层 HTTP
 * 工具混在一起，难以做能力市场、租户开关、版本发布、Agent Card 映射和运营治理。
 */
@Service
@RequiredArgsConstructor
public class AgentSkillRegistryService {

    /**
     * DataSmart Skill descriptor 版本。
     *
     * <p>该版本代表“单个 Skill 的机器可消费描述符”契约，不等同于市场摘要版本。
     * Python Runtime、智能网关和未来 MCP/A2A 适配层会依赖该值判断字段语义是否兼容。
     */
    private static final String SKILL_DESCRIPTOR_SCHEMA_VERSION = "datasmart.agent.skill.v1";

    /**
     * Skill descriptor 类型标识。
     *
     * <p>当前固定为内部 DataSmart 类型，而不是直接声明为外部 Agent Card，
     * 是为了保留租户权限、审计、记忆策略等平台治理字段。
     */
    private static final String SKILL_DESCRIPTOR_TYPE = "DATASMART_AGENT_SKILL";

    /**
     * 协议提示。
     *
     * <p>AGENT_CARD_STYLE 表示字段组织方式借鉴 Agent Card 的能力描述思路，
     * 但当前还不是完整外部协议导出，后续可以在适配层转换。
     */
    private static final String SKILL_DESCRIPTOR_PROTOCOL_HINT = "AGENT_CARD_STYLE";

    /**
     * Skill Marketplace 摘要 schema 版本。
     *
     * <p>该版本代表“整个平台 Skill 市场治理概览”契约。它与单个 Skill descriptor 分开，
     * 这样后续新增筛选维度、租户启停统计、版本发布统计时，不会影响 Python Runtime 对单个 Skill 的解析。
     */
    private static final String SKILL_MARKETPLACE_SUMMARY_SCHEMA_VERSION = "datasmart.agent.skill.marketplace.v1";

    private final AgentRuntimeProperties runtimeProperties;
    private final AgentSkillRegistryProperties skillProperties;

    /**
     * 查询 Skill 描述符列表。
     *
     * <p>支持按治理域、风险等级和启用状态过滤。当前 Skill 数量较小，内存过滤足够；
     * 后续迁移数据库后再补分页、版本、租户维度和发布状态过滤。
     *
     * @param domain 可选治理域过滤，例如 DATA_QUALITY、TASK_MANAGEMENT
     * @param riskLevel 可选风险等级过滤，例如 LOW、MEDIUM、HIGH
     * @param enabledOnly 是否只返回已启用 Skill；生产规划入口通常应传 true
     * @return 经过默认值补齐、归一化和排序后的 Skill descriptor 列表
     */
    public List<AgentSkillDescriptorView> listSkillDescriptors(String domain,
                                                               String riskLevel,
                                                               Boolean enabledOnly) {
        ensureRuntimeEnabled();
        return allSkillDescriptors().stream()
                .filter(item -> domain == null || domain.isBlank() || item.domain().equals(normalize(domain)))
                .filter(item -> riskLevel == null || riskLevel.isBlank()
                        || item.governance().riskLevel().equals(normalize(riskLevel)))
                .filter(item -> !Boolean.TRUE.equals(enabledOnly) || Boolean.TRUE.equals(item.governance().enabled()))
                .toList();
    }

    /**
     * 查询单个 Skill 描述符。
     *
     * <p>Python Runtime 可以在选择某个 Skill 后再次按 skillCode 拉取详情，
     * 确认所需工具、记忆依赖和审批策略是否仍然有效。这样可以避免 Runtime 启动时缓存的目录
     * 与执行前控制面事实不一致。
     *
     * @param skillCode 稳定 Skill 编码
     * @return 单个 Skill descriptor
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

    /**
     * 生成 Agent Skill Marketplace 治理摘要。
     *
     * <p>该方法不执行 Skill，也不修改注册表，只把当前配置式注册表聚合为“市场首页/运营看板”可消费的摘要。
     * 这一步看起来只是统计，但对商业化产品很重要：
     * 1. 前端市场页需要知道有哪些领域、风险等级和审批策略可以筛选；
     * 2. Python Runtime 启动时可以快速判断远程 Skill 目录是否为空、是否全部高风险、是否缺少审计约束；
     * 3. 管理员可以看到禁用 Skill 是否大量堆积，从而判断是灰度策略、租户裁剪还是下游能力故障。
     *
     * @param includeDisabled 是否把禁用 Skill 纳入 facet 和风险统计。市场运营视角建议传 true；
     *                        Python Runtime 如果只关心可规划能力，可以传 false。
     * @return Skill 市场治理摘要，包含数量、筛选维度、风险提示和后续建设建议。
     */
    public AgentSkillMarketplaceSummaryView getMarketplaceSummary(Boolean includeDisabled) {
        ensureRuntimeEnabled();
        boolean shouldIncludeDisabled = Boolean.TRUE.equals(includeDisabled);
        List<AgentSkillDescriptorView> registryDescriptors = allSkillDescriptors();
        List<AgentSkillDescriptorView> visibleDescriptors = registryDescriptors.stream()
                .filter(item -> shouldIncludeDisabled || Boolean.TRUE.equals(item.governance().enabled()))
                .toList();

        long enabledSkillCount = visibleDescriptors.stream()
                .filter(item -> Boolean.TRUE.equals(item.governance().enabled()))
                .count();
        long disabledSkillCount = visibleDescriptors.size() - enabledSkillCount;
        long highRiskSkillCount = visibleDescriptors.stream()
                .filter(item -> "HIGH".equals(item.governance().riskLevel()))
                .count();
        long approvalRequiredSkillCount = visibleDescriptors.stream()
                .filter(this::requiresReviewOrApproval)
                .count();
        long auditRequiredSkillCount = visibleDescriptors.stream()
                .filter(item -> Boolean.TRUE.equals(item.governance().auditRequired()))
                .count();
        long tenantScopedSkillCount = visibleDescriptors.stream()
                .filter(item -> Boolean.TRUE.equals(item.governance().tenantScoped()))
                .count();
        long projectScopedSkillCount = visibleDescriptors.stream()
                .filter(item -> Boolean.TRUE.equals(item.governance().projectScoped()))
                .count();

        return new AgentSkillMarketplaceSummaryView(
                SKILL_MARKETPLACE_SUMMARY_SCHEMA_VERSION,
                registryDescriptors.size(),
                visibleDescriptors.size(),
                enabledSkillCount,
                disabledSkillCount,
                highRiskSkillCount,
                approvalRequiredSkillCount,
                auditRequiredSkillCount,
                tenantScopedSkillCount,
                projectScopedSkillCount,
                summarizeFacet("DOMAIN", visibleDescriptors, item -> List.of(item.domain())),
                summarizeFacet("RISK_LEVEL", visibleDescriptors, item -> List.of(item.governance().riskLevel())),
                summarizeFacet("APPROVAL_POLICY", visibleDescriptors, item -> List.of(item.governance().approvalPolicy())),
                summarizeFacet("MEMORY_DEPENDENCY", visibleDescriptors,
                        item -> item.memory().memoryDependencies().isEmpty()
                                ? List.of("NONE")
                                : item.memory().memoryDependencies()),
                buildOperationalWarnings(
                        visibleDescriptors,
                        disabledSkillCount,
                        highRiskSkillCount,
                        approvalRequiredSkillCount,
                        auditRequiredSkillCount,
                        tenantScopedSkillCount,
                        projectScopedSkillCount
                ),
                buildRecommendedActions(visibleDescriptors, highRiskSkillCount, disabledSkillCount)
        );
    }

    private void ensureRuntimeEnabled() {
        if (!Boolean.TRUE.equals(runtimeProperties.getEnabled())) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT, "Agent Runtime 当前未启用");
        }
    }

    /**
     * 读取完整 Skill descriptor 快照。
     *
     * <p>列表接口和市场摘要都基于同一套转换逻辑，避免出现“列表里看到的字段”和“市场统计使用的字段”
     * 默认值口径不一致。例如 domain 为空时统一归一为 GENERAL_GOVERNANCE，风险为空时统一归一为 LOW。
     */
    private List<AgentSkillDescriptorView> allSkillDescriptors() {
        return skillProperties.getSkillRegistry().entrySet().stream()
                .map(this::toDescriptor)
                .sorted(Comparator.comparing(AgentSkillDescriptorView::skillCode))
                .toList();
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

    /**
     * 判断 Skill 是否需要进入“审批或复核”治理流。
     *
     * <p>这里把非 NONE 的 approvalPolicy 都视为需要治理动作，是因为 DRAFT_REVIEW、AUDIT_ONLY、
     * HUMAN_APPROVAL_REQUIRED 虽然强度不同，但都意味着模型不能把 Skill 当成完全无治理的普通能力。
     * 后续如果审批策略枚举化，可以把该逻辑升级为更精细的策略表。
     */
    private boolean requiresReviewOrApproval(AgentSkillDescriptorView item) {
        String approvalPolicy = item.governance().approvalPolicy();
        return approvalPolicy != null && !approvalPolicy.isBlank() && !"NONE".equals(approvalPolicy);
    }

    /**
     * 聚合一个 Skill 市场筛选维度。
     *
     * <p>Facet 统计同时保存 total/enabled/disabled 三个计数。这样前端既能展示“数据质量领域有多少能力”，
     * 也能提示“其中几个已经禁用”。这对灰度发布和客户现场问题定位很有帮助：如果用户问为什么某个能力
     * 不可用，管理员可以先看它是未注册、已禁用，还是被风险/审批策略拦截。
     */
    private List<AgentSkillMarketplaceFacetView> summarizeFacet(String facetType,
                                                                List<AgentSkillDescriptorView> descriptors,
                                                                Function<AgentSkillDescriptorView, List<String>> extractor) {
        Map<String, FacetAccumulator> accumulators = new TreeMap<>();
        for (AgentSkillDescriptorView descriptor : descriptors) {
            List<String> values = extractor.apply(descriptor);
            if (values == null || values.isEmpty()) {
                values = List.of("UNKNOWN");
            }
            for (String rawValue : values) {
                String value = normalizeText(rawValue, "UNKNOWN");
                accumulators.computeIfAbsent(value, ignored -> new FacetAccumulator())
                        .accept(descriptor);
            }
        }
        return accumulators.entrySet().stream()
                .map(entry -> entry.getValue().toView(facetType, entry.getKey()))
                .toList();
    }

    /**
     * 构建 Skill 市场运营风险提示。
     *
     * <p>提示文案刻意保持低敏，只描述目录治理状态，不暴露租户、项目、用户或具体业务数据。
     * 它们适合出现在管理页、启动诊断或后续 Grafana 文本面板中。
     */
    private List<String> buildOperationalWarnings(List<AgentSkillDescriptorView> visibleDescriptors,
                                                  long disabledSkillCount,
                                                  long highRiskSkillCount,
                                                  long approvalRequiredSkillCount,
                                                  long auditRequiredSkillCount,
                                                  long tenantScopedSkillCount,
                                                  long projectScopedSkillCount) {
        List<String> warnings = new ArrayList<>();
        if (visibleDescriptors.isEmpty()) {
            warnings.add("当前没有可见 Agent Skill，Python Runtime 只能使用本地默认能力或完全依赖工具级规划。");
            return warnings;
        }
        if (disabledSkillCount > 0) {
            warnings.add("当前摘要包含已禁用 Skill，应在市场页明确展示下线原因、灰度状态或租户裁剪策略。");
        }
        if (highRiskSkillCount > 0 && approvalRequiredSkillCount < highRiskSkillCount) {
            warnings.add("存在 HIGH 风险 Skill 未配置审批或复核策略，生产环境应避免模型直接启用高风险能力。");
        }
        if (auditRequiredSkillCount < visibleDescriptors.size()) {
            warnings.add("存在未声明强制审计的 Skill，后续执行链路可能缺少合规追责证据。");
        }
        if (tenantScopedSkillCount < visibleDescriptors.size() || projectScopedSkillCount < visibleDescriptors.size()) {
            warnings.add("存在未声明租户或项目隔离的 Skill，商业化多租户部署前需要复核隔离边界。");
        }
        if (warnings.isEmpty()) {
            warnings.add("当前 Skill 市场治理摘要未发现明显配置缺口，但仍需结合租户开关、版本发布和真实权限快照复核。");
        }
        return warnings;
    }

    /**
     * 构建下一步产品建设建议。
     *
     * <p>这些建议不是错误提示，而是为了满足项目“主动从需求、场景、功能、性能角度思考”的要求。
     * 也就是说，每次补一个功能，都顺手把自然延伸出的商业化建设方向留下来，避免项目长期停留在 demo。
     */
    private List<String> buildRecommendedActions(List<AgentSkillDescriptorView> visibleDescriptors,
                                                 long highRiskSkillCount,
                                                 long disabledSkillCount) {
        List<String> actions = new ArrayList<>();
        actions.add("将配置式 Skill 注册表逐步迁移为带版本、发布状态、租户启停和灰度批次的数据库市场。");
        actions.add("为 Skill 市场增加权限包绑定、审批策略模板、审计 outbox 和变更历史，避免能力发布不可追踪。");
        if (highRiskSkillCount > 0) {
            actions.add("对 HIGH 风险 Skill 增加强制人工确认、执行前 dry-run、服务账号授权预检和高风险操作告警。");
        }
        if (disabledSkillCount > 0) {
            actions.add("为禁用 Skill 增加下线原因、影响租户、预计恢复时间和替代能力推荐，减少运营黑盒。");
        }
        if (!visibleDescriptors.isEmpty()) {
            actions.add("将 domain/risk/approval/memory facets 暴露给前端和 Python Runtime，用统一口径驱动筛选和启动诊断。");
        }
        return actions;
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

    /**
     * Facet 聚合计数器。
     *
     * <p>该内部类只在服务内部使用，不作为 API 契约暴露。这样后续如果要增加 runningCount、draftCount、
     * tenantEnabledCount 等复杂字段，可以先调整内部聚合，再选择性扩展 `AgentSkillMarketplaceFacetView`。
     */
    private static final class FacetAccumulator {

        private long totalCount;
        private long enabledCount;
        private long disabledCount;

        private void accept(AgentSkillDescriptorView descriptor) {
            totalCount++;
            if (Boolean.TRUE.equals(descriptor.governance().enabled())) {
                enabledCount++;
            } else {
                disabledCount++;
            }
        }

        private AgentSkillMarketplaceFacetView toView(String facetType, String value) {
            return new AgentSkillMarketplaceFacetView(facetType, value, totalCount, enabledCount, disabledCount);
        }
    }
}
