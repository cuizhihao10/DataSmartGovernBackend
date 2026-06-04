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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Agent Skill 发布 Manifest 服务。
 *
 * <p>该服务负责把 Java 控制面的 Skill descriptor 快照转换为“可发布 Manifest”。
 * 它不直接修改 Skill 注册表，也不执行发布审批；当前目标是先固定发布数据契约，让 Python Runtime、
 * 智能网关和未来 MCP/A2A 适配层可以按版本、指纹和发布状态同步 Skill 能力。</p>
 *
 * <p>为什么独立成一个服务，而不是继续扩展 {@link AgentSkillRegistryService}？
 * `AgentSkillRegistryService` 已经负责 descriptor 列表、单条详情和 Marketplace 摘要；
 * Manifest 又引入指纹、发布状态、消费建议和协议兼容说明。如果继续塞进注册服务，后续数据库发布流、
 * 灰度批次、租户开关和审计 outbox 会让单文件快速膨胀。独立服务可以保持职责清晰。</p>
 *
 * <p>与 MCP 的关系：
 * 当前输出是 MCP-style Skill Manifest，而不是完整 MCP Server。MCP 工具规范强调可发现工具、结构化输入、
 * 资源链接和客户端确认；DataSmart 的 Skill 位于工具之上，描述的是一组工具、记忆、审批和审计策略的能力包。
 * 因此我们先输出内部 Manifest，后续可以由适配层转换为 MCP prompts/resources/tools 或 A2A Agent Card。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentSkillPublicationManifestService {

    private static final String MANIFEST_SCHEMA_VERSION = "datasmart.agent.skill.publication-manifest.v1";
    private static final String MANIFEST_TYPE = "DATASMART_AGENT_SKILL_PUBLICATION_MANIFEST";
    private static final String PROTOCOL_HINT = "MCP_STYLE_SKILL_MANIFEST";
    private static final String DESCRIPTOR_SCHEMA_VERSION = "datasmart.agent.skill.v1";
    private static final String PUBLICATION_MODE_SNAPSHOT = "SNAPSHOT";

    private final AgentSkillRegistryService skillRegistryService;

    /**
     * 生成 Skill 发布 Manifest。
     *
     * @param includeDisabled 是否包含禁用 Skill。运行时同步默认 false，市场运营或下线排查可传 true。
     * @param domain 可选治理域过滤。
     * @param riskLevel 可选风险等级过滤。
     * @return 可被 Python Runtime、智能网关和前端市场页消费的发布快照。
     */
    public AgentSkillPublicationManifestView buildManifest(Boolean includeDisabled,
                                                           String domain,
                                                           String riskLevel) {
        boolean shouldIncludeDisabled = Boolean.TRUE.equals(includeDisabled);
        List<AgentSkillDescriptorView> descriptors = skillRegistryService.listSkillDescriptors(
                domain,
                riskLevel,
                !shouldIncludeDisabled
        );
        List<AgentSkillPublicationItemView> items = descriptors.stream()
                .map(this::toPublicationItem)
                .toList();
        String contentFingerprint = fingerprintManifest(items, shouldIncludeDisabled, domain, riskLevel);

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
                consumerGuidance(shouldIncludeDisabled),
                compatibilityNotes(),
                recommendedActions(items)
        );
    }

    private AgentSkillPublicationItemView toPublicationItem(AgentSkillDescriptorView descriptor) {
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
                publicationWarnings(descriptor, publicationState)
        );
    }

    /**
     * 判断 Skill 是否适合进入默认发布目录。
     *
     * <p>发布状态是给运行时和市场页看的稳定机器字段：
     * - READY：可进入默认运行时同步；
     * - DISABLED：已被配置关闭；
     * - NEEDS_APPROVAL_POLICY：高风险 Skill 缺少审批策略；
     * - NEEDS_AUDIT_POLICY：缺少强制审计；
     * - NEEDS_ISOLATION_POLICY：缺少租户或项目隔离声明。</p>
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

    private List<String> publicationWarnings(AgentSkillDescriptorView descriptor, String publicationState) {
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
            warnings.add("该 Skill 未声明工具依赖，运行时应确认它是纯解释型能力而不是遗漏配置。");
        }
        if (warnings.isEmpty()) {
            warnings.add("该 Skill 发布摘要未发现明显治理缺口，仍需结合租户开关和权限包做执行前复核。");
        }
        return warnings;
    }

    private List<String> descriptorEndpoints(String skillCode) {
        return List.of(
                "/agent-runtime/skills/" + skillCode + "/descriptor",
                "/api/agent/skills/" + skillCode + "/descriptor"
        );
    }

    private List<String> consumerGuidance(boolean includeDisabled) {
        List<String> guidance = new ArrayList<>();
        guidance.add("Python Runtime 应优先比较 contentFingerprint；未变化时可以复用本地 Skill 缓存。");
        guidance.add("运行时默认只消费 publicationState=READY 的 Skill；非 READY 条目应进入诊断或市场页提示。");
        guidance.add("执行前仍必须调用 Java 控制面的工具审批、权限预检、沙箱和 runtime-protection，Manifest 不能替代执行授权。");
        if (includeDisabled) {
            guidance.add("当前 Manifest 包含禁用 Skill，仅适合市场运营、下线排查或管理员诊断，不适合直接给模型规划。");
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
        actions.add("将配置式 Skill 注册表迁移为数据库发布表，增加版本号、发布状态、灰度批次、租户开关和变更审计。");
        actions.add("为 Skill Manifest 增加租户级过滤和权限包绑定，避免不同套餐/角色看到同一份能力目录。");
        actions.add("Python Runtime 应把 Manifest 指纹写入启动诊断和 runtime event，方便排查运行时使用了哪一版 Skill 目录。");
        if (items.stream().anyMatch(item -> !"READY".equals(item.publicationState()))) {
            actions.add("优先处理非 READY Skill 的审批、审计、隔离或禁用原因，避免能力市场出现灰色发布状态。");
        }
        return actions;
    }

    private String fingerprintManifest(List<AgentSkillPublicationItemView> items,
                                       boolean includeDisabled,
                                       String domain,
                                       String riskLevel) {
        StringBuilder builder = new StringBuilder();
        builder.append(MANIFEST_SCHEMA_VERSION).append('|')
                .append(includeDisabled).append('|')
                .append(normalizeFilter(domain)).append('|')
                .append(normalizeFilter(riskLevel));
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

    private String nullSafe(Object value) {
        return Objects.toString(value, "");
    }
}
