/**
 * @Author : Cui
 * @Date: 2026/07/02 03:05
 * @Description DataSmart Govern Backend - AgentSkillPublicationFingerprintSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service;

import com.czh.datasmart.govern.agent.controller.dto.AgentSkillDescriptorView;
import com.czh.datasmart.govern.agent.controller.dto.AgentSkillPublicationItemView;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Skill Manifest 指纹和过滤值规范化支持。
 *
 * <p>Manifest 指纹是缓存失效、运行时准入快照和审计关联的稳定依据。该支持类只处理低敏 descriptor
 * 元数据和 scope 标识，不读取 Skill 正文、prompt、工具参数或凭据。算法固定使用 SHA-256；如果 JDK
 * 缺少该标准算法则直接失败，避免生成随机值破坏跨实例一致性。
 */
final class AgentSkillPublicationFingerprintSupport {

    private AgentSkillPublicationFingerprintSupport() {
    }

    static String fingerprintManifest(String schemaVersion,
                                      List<AgentSkillPublicationItemView> items,
                                      boolean includeDisabled,
                                      String domain,
                                      String riskLevel,
                                      String tenantId,
                                      String projectId) {
        StringBuilder builder = new StringBuilder();
        builder.append(schemaVersion).append('|')
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

    static String fingerprintDescriptor(AgentSkillDescriptorView descriptor) {
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

    static String normalizeFilter(String value) {
        return value == null || value.isBlank()
                ? "ALL"
                : value.trim().replace('-', '_').toUpperCase(Locale.ROOT);
    }

    static String normalizeScopeFilter(String value) {
        return value == null || value.isBlank() ? "UNSCOPED" : value.trim();
    }

    private static String sha256(String source) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256，无法生成 Skill Manifest 指纹", exception);
        }
    }

    private static String nullSafe(Object value) {
        return Objects.toString(value, "");
    }
}
