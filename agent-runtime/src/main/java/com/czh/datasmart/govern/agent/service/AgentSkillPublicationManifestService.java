/**
 * @Author : Cui
 * @Date: 2026/06/04 18:36
 * @Description DataSmart Govern Backend - AgentSkillPublicationManifestService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service;

import com.czh.datasmart.govern.agent.controller.dto.AgentSkillDescriptorView;
import com.czh.datasmart.govern.agent.controller.dto.AgentSkillPublicationItemView;
import com.czh.datasmart.govern.agent.controller.dto.AgentSkillPublicationManifestView;
import com.czh.datasmart.govern.agent.service.skill.AgentSkillPublicationLifecycleQuery;
import com.czh.datasmart.govern.agent.service.skill.AgentSkillPublicationLifecycleStatus;
import com.czh.datasmart.govern.agent.service.skill.AgentSkillPublicationLifecycleStore;
import com.czh.datasmart.govern.agent.service.skill.AgentSkillPublicationRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Agent Skill 发布 Manifest 服务。
 *
 * <p>Manifest 可以理解为“运行时可消费的能力目录快照”：Python AI Runtime、智能网关、Skill Marketplace 页面、
 * 以及未来 MCP/A2A 适配层，都可以通过它获知当前有哪些 Skill、每个 Skill 的风险等级、审批策略、依赖工具、
 * 依赖权限、记忆依赖、发布状态和内容指纹。它不是执行入口，也不是授权入口，而是执行前能力发现和缓存判断入口。</p>
 *
 * <p>本服务当前同时支持两类来源：
 * 1. 静态配置注册表：平台内置 Skill，来自 {@link AgentSkillRegistryService}；
 * 2. 发布生命周期控制面：用户或运营流程创建、审核通过后的 READY Skill，来自 {@link AgentSkillPublicationLifecycleStore}。
 * 这样做可以让项目从“只读配置目录”逐步闭环到“可创建、可审核、可发布、可消费”的 Skill Marketplace。</p>
 *
 * <p>非常重要的安全边界：生命周期发布单即使只保存低敏元数据，也属于租户/项目范围内的治理事实。
 * 因此只有请求明确携带 tenantId 与 projectId 时，本服务才会把生命周期发布事实合并进 Manifest；
 * 老的三参调用仍只返回静态注册表内容，避免无范围请求跨租户、跨项目看到不该暴露的 Skill。</p>
 */
@Service
public class AgentSkillPublicationManifestService {

    private static final String MANIFEST_SCHEMA_VERSION = "datasmart.agent.skill.publication-manifest.v1";
    private static final String MANIFEST_TYPE = "DATASMART_AGENT_SKILL_PUBLICATION_MANIFEST";
    private static final String PROTOCOL_HINT = "MCP_STYLE_SKILL_MANIFEST";
    private static final String DESCRIPTOR_SCHEMA_VERSION = "datasmart.agent.skill.v1";
    private static final String PUBLICATION_MODE_SNAPSHOT = "SNAPSHOT";
    private static final int LIFECYCLE_MANIFEST_QUERY_LIMIT = 200;

    /**
     * 静态 Skill 注册服务。
     *
     * <p>它负责读取配置中的内置 Skill descriptor。Manifest 服务不会把注册服务变成写侧控制面，
     * 只是把 descriptor 转换成运行时更容易消费的发布摘要。</p>
     */
    private final AgentSkillRegistryService skillRegistryService;

    /**
     * Skill 发布生命周期仓储。
     *
     * <p>使用 Optional 的原因是为了保持分阶段闭环：没有启用发布生命周期 store 时，
     * Manifest 仍然能只基于静态 registry 工作；启用 store 后，才在有租户/项目范围的请求中合并 READY 发布事实。
     * 后续从内存 store 切到 MySQL/JDBC store 时，Manifest 服务也不需要重写业务合并规则。</p>
     */
    private final Optional<AgentSkillPublicationLifecycleStore> lifecycleStore;

    /**
     * Spring 运行时使用的构造器。
     *
     * @param skillRegistryService 静态 Skill 注册服务。
     * @param lifecycleStore 可选发布生命周期仓储，Spring 会在没有 bean 时注入空 Optional。
     */
    @Autowired
    public AgentSkillPublicationManifestService(AgentSkillRegistryService skillRegistryService,
                                                Optional<AgentSkillPublicationLifecycleStore> lifecycleStore) {
        this.skillRegistryService = skillRegistryService;
        this.lifecycleStore = lifecycleStore == null ? Optional.empty() : lifecycleStore;
    }

    /**
     * 测试和极简本地构造使用的兼容构造器。
     *
     * <p>老测试只验证“静态 registry -> Manifest”的映射，不应为了生命周期功能被迫创建仓储对象。
     * 保留该构造器也能提醒维护者：生命周期发布事实是 Manifest 的增强来源，不是生成静态 Manifest 的硬前置。</p>
     */
    public AgentSkillPublicationManifestService(AgentSkillRegistryService skillRegistryService) {
        this(skillRegistryService, Optional.empty());
    }

    /**
     * 生成兼容旧调用方的 Skill 发布 Manifest。
     *
     * <p>该方法不携带 tenantId/projectId，因此只返回静态注册表 Skill。
     * 这不是功能遗漏，而是安全默认值：无范围请求不能读取租户/项目级发布单。</p>
     *
     * @param includeDisabled 是否包含禁用 Skill。运行时默认 false；市场诊断或运营后台可传 true。
     * @param domain 可选治理域过滤，例如 DATA_QUALITY、TASK_MANAGEMENT。
     * @param riskLevel 可选风险等级过滤，例如 LOW、MEDIUM、HIGH。
     * @return 可被运行时、网关和市场页消费的发布快照。
     */
    public AgentSkillPublicationManifestView buildManifest(Boolean includeDisabled,
                                                           String domain,
                                                           String riskLevel) {
        return buildManifest(includeDisabled, domain, riskLevel, null, null);
    }

    /**
     * 生成带租户/项目范围的 Skill 发布 Manifest。
     *
     * <p>该重载是 Skill Marketplace 闭环的关键桥接点：静态 registry 解决“平台内置能力有哪些”，
     * 生命周期 store 解决“客户/运营创建并审核通过的能力能否被运行时发现”。最终输出仍是同一个 Manifest DTO，
     * 因此 Python Runtime 和智能网关可以用一套缓存、指纹、诊断和回放逻辑消费两类来源。</p>
     *
     * <p>合并生命周期发布事实时只读取低敏字段，不读取、不返回、不派生 prompt、SQL、工具参数、样本数据、
     * 模型输出、凭据、内部 endpoint 或脚本正文。Manifest 只能告诉运行时“某个 Skill 已具备发布资格”，
     * 不能替代权限中心、readiness、工具预算、人工审批和审计。</p>
     *
     * @param includeDisabled 是否包含禁用/下线条目。运行时默认 false；管理诊断场景可为 true。
     * @param domain 可选治理域过滤。
     * @param riskLevel 可选风险等级过滤。
     * @param tenantId 可信租户范围；为空时不读取生命周期发布单。
     * @param projectId 可信项目范围；为空时不读取生命周期发布单。
     * @return 可被运行时、网关和市场页消费的低敏 Manifest 快照。
     */
    public AgentSkillPublicationManifestView buildManifest(Boolean includeDisabled,
                                                           String domain,
                                                           String riskLevel,
                                                           String tenantId,
                                                           String projectId) {
        boolean shouldIncludeDisabled = Boolean.TRUE.equals(includeDisabled);
        List<AgentSkillDescriptorView> descriptors = skillRegistryService.listSkillDescriptors(
                domain,
                riskLevel,
                !shouldIncludeDisabled
        );

        /*
         * 使用 LinkedHashMap 的目的有两个：
         * 1. 保持静态 registry 的原始顺序，让旧调用方看到的顺序尽量稳定；
         * 2. 用 skillCode 去重，避免同一个能力既来自静态配置又来自生命周期发布单时出现重复候选。
         * 当前策略是“静态配置优先”，因为内置 Skill 通常经过代码评审和配置发布，生命周期 store 仍处于收敛阶段。
         */
        Map<String, AgentSkillPublicationItemView> itemsBySkillCode = new LinkedHashMap<>();
        descriptors.stream()
                .map(this::toRegistryPublicationItem)
                .forEach(item -> itemsBySkillCode.put(item.skillCode(), item));

        boolean lifecycleScopedLookup = hasLifecycleScope(tenantId, projectId);
        if (lifecycleScopedLookup) {
            mergeLifecyclePublicationItems(
                    itemsBySkillCode,
                    shouldIncludeDisabled,
                    domain,
                    riskLevel,
                    tenantId,
                    projectId
            );
        }

        List<AgentSkillPublicationItemView> items = new ArrayList<>(itemsBySkillCode.values());
        String contentFingerprint = fingerprintManifest(
                items,
                shouldIncludeDisabled,
                domain,
                riskLevel,
                lifecycleScopedLookup ? tenantId : null,
                lifecycleScopedLookup ? projectId : null
        );

        return new AgentSkillPublicationManifestView(
                MANIFEST_SCHEMA_VERSION,
                MANIFEST_TYPE,
                PROTOCOL_HINT,
                DESCRIPTOR_SCHEMA_VERSION,
                PUBLICATION_MODE_SNAPSHOT,
                contentFingerprint,
                Instant.now(),
                shouldIncludeDisabled,
                normalizeFilter(domain),
                normalizeFilter(riskLevel),
                items.size(),
                items,
                consumerGuidance(shouldIncludeDisabled, lifecycleScopedLookup),
                compatibilityNotes(),
                recommendedActions(items)
        );
    }

    /**
     * 把静态 Skill descriptor 转成 Manifest 条目。
     *
     * <p>descriptor 是完整能力描述，Manifest item 是运行时发布摘要。这里不会复制所有字段，
     * 只保留运行时发现、缓存、治理判断和市场展示最需要的字段。</p>
     */
    private AgentSkillPublicationItemView toRegistryPublicationItem(AgentSkillDescriptorView descriptor) {
        String publicationState = publicationState(descriptor);
        return new AgentSkillPublicationItemView(
                descriptor.skillCode(),
                descriptor.displayName(),
                descriptor.domain(),
                publicationState,
                fingerprintDescriptor(descriptor),
                descriptorEndpoints(descriptor.skillCode()),
                descriptor.governance().enabled(),
                descriptor.governance().riskLevel(),
                descriptor.governance().approvalPolicy(),
                descriptor.governance().auditRequired(),
                descriptor.governance().tenantScoped(),
                descriptor.governance().projectScoped(),
                descriptor.requiredTools(),
                descriptor.requiredPermissions(),
                descriptor.memory().memoryDependencies(),
                registryPublicationWarnings(descriptor, publicationState)
        );
    }

    /**
     * 合并生命周期控制面中的发布单。
     *
     * <p>默认只合并 READY，因为 READY 才代表已通过发布审核、可以进入运行时候选目录。
     * 当 includeDisabled=true 时额外合并 DEPRECATED，供管理后台、审计回放或市场诊断查看下线历史。</p>
     */
    private void mergeLifecyclePublicationItems(Map<String, AgentSkillPublicationItemView> itemsBySkillCode,
                                                boolean includeDisabled,
                                                String domain,
                                                String riskLevel,
                                                String tenantId,
                                                String projectId) {
        lifecycleStore.ifPresent(store -> {
            addLifecyclePublicationItems(
                    store,
                    itemsBySkillCode,
                    domain,
                    riskLevel,
                    tenantId,
                    projectId,
                    AgentSkillPublicationLifecycleStatus.READY
            );
            if (includeDisabled) {
                addLifecyclePublicationItems(
                        store,
                        itemsBySkillCode,
                        domain,
                        riskLevel,
                        tenantId,
                        projectId,
                        AgentSkillPublicationLifecycleStatus.DEPRECATED
                );
            }
        });
    }

    /**
     * 查询指定状态的生命周期发布单，并把它们转成 Manifest 条目。
     *
     * <p>仓储查询会先用 tenantId/projectId/domain/status 收口，再由服务层补充 riskLevel 过滤。
     * 当前内存 store 已按 updatedAt 倒序返回，因此同一个 skillCode 多版本共存时，较新的记录会先进入 Map；
     * 后续重复 skillCode 会被 putIfAbsent 忽略，避免 Manifest 暴露同一能力的多个版本候选。</p>
     */
    private void addLifecyclePublicationItems(AgentSkillPublicationLifecycleStore store,
                                              Map<String, AgentSkillPublicationItemView> itemsBySkillCode,
                                              String domain,
                                              String riskLevel,
                                              String tenantId,
                                              String projectId,
                                              AgentSkillPublicationLifecycleStatus status) {
        List<AgentSkillPublicationRecord> records = store.query(new AgentSkillPublicationLifecycleQuery(
                trimToNull(tenantId),
                trimToNull(projectId),
                null,
                lifecycleDomainFilter(domain),
                status,
                LIFECYCLE_MANIFEST_QUERY_LIMIT
        ));
        records.stream()
                .filter(record -> matchesRiskLevel(record, riskLevel))
                .map(this::toLifecyclePublicationItem)
                .forEach(item -> itemsBySkillCode.putIfAbsent(item.skillCode(), item));
    }

    /**
     * 把发布生命周期记录转换成 Manifest 条目。
     *
     * <p>这里返回的是发布单查询 endpoint，而不是静态 descriptor endpoint。
     * 原因是生命周期发布单当前只保存低敏治理元数据，并不等价于完整 descriptor；运行时如果需要进一步确认，
     * 应回查发布单状态、内容指纹和治理策略，而不是假设它一定存在静态 descriptor 路由。</p>
     */
    private AgentSkillPublicationItemView toLifecyclePublicationItem(AgentSkillPublicationRecord record) {
        boolean enabled = record.status() == AgentSkillPublicationLifecycleStatus.READY;
        return new AgentSkillPublicationItemView(
                record.skillCode(),
                record.displayName(),
                record.domain(),
                record.status().name(),
                record.contentFingerprint(),
                lifecyclePublicationEndpoints(record.publicationId()),
                enabled,
                record.riskLevel(),
                record.approvalPolicy(),
                record.auditRequired(),
                record.tenantScoped(),
                record.projectScoped(),
                record.requiredTools(),
                record.requiredPermissions(),
                record.memoryDependencies(),
                lifecyclePublicationWarnings(record)
        );
    }

    /**
     * 判断静态 Skill 是否适合进入默认发布目录。
     *
     * <p>发布状态是给运行时和市场页看的稳定机器字段：
     * READY 表示治理策略完整，可进入默认候选；DISABLED 表示配置关闭；
     * NEEDS_* 表示该 Skill 仍缺少审计、隔离或审批策略，不能被运行时直接当作可执行能力。</p>
     */
    private String publicationState(AgentSkillDescriptorView descriptor) {
        if (!Boolean.TRUE.equals(descriptor.governance().enabled())) {
            return "DISABLED";
        }
        if (!Boolean.TRUE.equals(descriptor.governance().auditRequired())) {
            return "NEEDS_AUDIT_POLICY";
        }
        if (!Boolean.TRUE.equals(descriptor.governance().tenantScoped())
                || !Boolean.TRUE.equals(descriptor.governance().projectScoped())) {
            return "NEEDS_ISOLATION_POLICY";
        }
        if ("HIGH".equals(descriptor.governance().riskLevel())
                && "NONE".equals(descriptor.governance().approvalPolicy())) {
            return "NEEDS_APPROVAL_POLICY";
        }
        return "READY";
    }

    private List<String> registryPublicationWarnings(AgentSkillDescriptorView descriptor, String publicationState) {
        List<String> warnings = new ArrayList<>();
        if ("DISABLED".equals(publicationState)) {
            warnings.add("该 Skill 当前已禁用，默认不应进入 Python Runtime 自动规划目录。");
        }
        if ("NEEDS_APPROVAL_POLICY".equals(publicationState)) {
            warnings.add("HIGH 风险 Skill 缺少审批策略，发布前应补充人工确认或草案复核。");
        }
        if ("NEEDS_AUDIT_POLICY".equals(publicationState)) {
            warnings.add("该 Skill 未声明强制审计，发布后可能缺少合规追责证据。");
        }
        if ("NEEDS_ISOLATION_POLICY".equals(publicationState)) {
            warnings.add("该 Skill 未完整声明租户/项目隔离，商业化多租户部署前需要复核。");
        }
        if (descriptor.requiredTools().isEmpty()) {
            warnings.add("该 Skill 未声明工具依赖，运行时应确认它是纯解释型能力，而不是遗漏配置。");
        }
        if (warnings.isEmpty()) {
            warnings.add("该 Skill 发布摘要未发现明显治理缺口，仍需结合租户开关和权限包做执行前复核。");
        }
        return warnings;
    }

    private List<String> lifecyclePublicationWarnings(AgentSkillPublicationRecord record) {
        List<String> warnings = new ArrayList<>();
        warnings.add("该 Skill 来自发布生命周期控制面，已经按租户和项目范围收口；运行时仍必须执行权限、预算、readiness、审计和人工确认策略。");
        if (record.status() == AgentSkillPublicationLifecycleStatus.DEPRECATED) {
            warnings.add("该 Skill 已下线，仅适合管理诊断、审计回放或市场页历史展示，不应进入模型自动规划候选集。");
        }
        if (record.requiredTools().isEmpty()) {
            warnings.add("该 Skill 未声明工具依赖，运行时应确认它确实是解释型能力，而不是发布时遗漏了工具配置。");
        }
        String normalizedRiskLevel = normalizeFilter(record.riskLevel());
        if ("HIGH".equals(normalizedRiskLevel) || "CRITICAL".equals(normalizedRiskLevel)) {
            warnings.add("高风险 Skill 即使处于 READY，也只能表示发布资格通过；真实执行前仍需结合权限中心和人工审批策略二次确认。");
        }
        return warnings;
    }

    private List<String> descriptorEndpoints(String skillCode) {
        return List.of(
                "/agent-runtime/skills/" + skillCode + "/descriptor",
                "/api/agent/skills/" + skillCode + "/descriptor"
        );
    }

    private List<String> lifecyclePublicationEndpoints(String publicationId) {
        return List.of(
                "/agent-runtime/skills/publications/" + publicationId,
                "/api/agent/skills/publications/" + publicationId
        );
    }

    private List<String> consumerGuidance(boolean includeDisabled, boolean lifecycleScopedLookup) {
        List<String> guidance = new ArrayList<>();
        guidance.add("Python Runtime 应优先比较 contentFingerprint；未变化时可以复用本地 Skill 缓存。");
        guidance.add("运行时默认只消费 publicationState=READY 的 Skill；非 READY 条目应进入诊断或市场页提示。");
        guidance.add("执行前仍必须调用 Java 控制面的工具审批、权限预检、沙箱和 runtime-protection，Manifest 不能替代执行授权。");
        if (lifecycleScopedLookup) {
            guidance.add("当前 Manifest 已按 tenantId/projectId 合并发布生命周期 READY 事实；消费者必须把该范围纳入本地缓存 key，避免跨项目复用能力目录。");
        } else {
            guidance.add("当前请求未携带完整 tenantId/projectId，因此 Manifest 只包含静态注册表 Skill，不会返回生命周期发布单，避免跨租户或跨项目暴露。");
        }
        if (includeDisabled) {
            guidance.add("当前 Manifest 包含禁用或下线 Skill，仅适合市场运营、下线排查或管理员诊断，不适合直接给模型规划。");
        }
        return guidance;
    }

    private List<String> compatibilityNotes() {
        return List.of(
                "当前 Manifest 是 DataSmart 内部 MCP-style 契约，不是完整 MCP JSON-RPC Server 响应。",
                "Skill 位于工具之上：它组织工具、记忆、审批和审计策略；真实工具 schema 仍由 MCP-style tool descriptor 提供。",
                "后续可把 READY Skill 转换为 MCP prompts/resources 或 A2A Agent Card 的能力声明。"
        );
    }

    private List<String> recommendedActions(List<AgentSkillPublicationItemView> items) {
        List<String> actions = new ArrayList<>();
        actions.add("继续把配置式 Skill 注册表迁移为数据库发布表，补齐版本号、灰度批次、租户开关、变更审计和 outbox。");
        actions.add("把 Manifest 指纹写入 Python Runtime 启动诊断和 runtime event，便于排查运行时实际使用了哪一版 Skill 目录。");
        if (items.stream().anyMatch(item -> item.descriptorEndpoints().stream()
                .anyMatch(endpoint -> endpoint.contains("/skills/publications/")))) {
            actions.add("Manifest 已包含发布生命周期事实；下一步应补 MySQL durable store、发布 outbox、租户可见性缓存失效和灰度回滚。");
        }
        if (items.stream().anyMatch(item -> !"READY".equals(item.publicationState()))) {
            actions.add("优先处理非 READY Skill 的审批、审计、隔离或禁用原因，避免能力市场出现灰色发布状态。");
        }
        return actions;
    }

    private String fingerprintManifest(List<AgentSkillPublicationItemView> items,
                                       boolean includeDisabled,
                                       String domain,
                                       String riskLevel,
                                       String tenantId,
                                       String projectId) {
        StringBuilder builder = new StringBuilder();
        builder.append(MANIFEST_SCHEMA_VERSION).append('|')
                .append(includeDisabled).append('|')
                .append(normalizeFilter(domain)).append('|')
                .append(normalizeFilter(riskLevel)).append('|')
                .append(normalizeScopeFilter(tenantId)).append('|')
                .append(normalizeScopeFilter(projectId));
        for (AgentSkillPublicationItemView item : items) {
            builder.append('|').append(item.skillCode()).append(':').append(item.contentFingerprint());
        }
        return sha256(builder.toString());
    }

    private String fingerprintDescriptor(AgentSkillDescriptorView descriptor) {
        String source = String.join("|",
                nullSafe(descriptor.schemaVersion()),
                nullSafe(descriptor.skillCode()),
                nullSafe(descriptor.displayName()),
                nullSafe(descriptor.description()),
                nullSafe(descriptor.domain()),
                String.join(",", descriptor.requiredTools()),
                String.join(",", descriptor.requiredPermissions()),
                String.join(",", descriptor.triggerKeywords()),
                nullSafe(descriptor.governance().riskLevel()),
                nullSafe(descriptor.governance().approvalPolicy()),
                String.valueOf(descriptor.governance().enabled()),
                String.valueOf(descriptor.governance().auditRequired()),
                String.valueOf(descriptor.governance().tenantScoped()),
                String.valueOf(descriptor.governance().projectScoped()),
                String.join(",", descriptor.memory().memoryDependencies()),
                nullSafe(descriptor.memory().defaultMemoryScope()),
                String.valueOf(descriptor.memory().retentionDays())
        );
        return sha256(source);
    }

    private String sha256(String source) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256，无法生成 Skill Manifest 指纹", exception);
        }
    }

    private String normalizeFilter(String value) {
        return value == null || value.isBlank()
                ? "ALL"
                : value.trim().replace('-', '_').toUpperCase(Locale.ROOT);
    }

    private String normalizeScopeFilter(String value) {
        return value == null || value.isBlank() ? "UNSCOPED" : value.trim();
    }

    private String lifecycleDomainFilter(String domain) {
        String normalized = normalizeFilter(domain);
        return "ALL".equals(normalized) ? null : normalized;
    }

    private boolean matchesRiskLevel(AgentSkillPublicationRecord record, String riskLevel) {
        String normalizedRiskFilter = normalizeFilter(riskLevel);
        return "ALL".equals(normalizedRiskFilter) || normalizedRiskFilter.equals(normalizeFilter(record.riskLevel()));
    }

    private boolean hasLifecycleScope(String tenantId, String projectId) {
        return tenantId != null && !tenantId.isBlank() && projectId != null && !projectId.isBlank();
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String nullSafe(Object value) {
        return Objects.toString(value, "");
    }
}
