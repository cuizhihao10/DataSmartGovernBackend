/**
 * @Author : Cui
 * @Date: 2026/06/26 23:43
 * @Description DataSmart Govern Backend - AgentToolActionArtifactMinioObjectLocator.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.config.AgentArtifactObjectStoreMinioProperties;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 低敏 artifactReference 到 MinIO objectName 的服务端定位器。
 *
 * <p>这个类是安全边界里很关键的一刀：HTTP 请求只能携带 `agent-artifact:`、`command-output:` 这类低敏引用，
 * 不能携带 bucket/key、URL 或真实文件路径。定位器根据服务端配置把低敏引用映射为 objectName，后续 MinIO SDK
 * 只接触这个 objectName。这样即使外部调用方伪造 `https://internal/bucket/key` 或 `../secret`，也会在这里
 * fail-closed，不会进入对象存储。</p>
 *
 * <p>该类不返回 bucket，不记录 objectName 日志，也不把 objectName 放入 HTTP 响应。objectName 只在
 * MinIO adapter 内部短生命周期使用。</p>
 */
@Component
public class AgentToolActionArtifactMinioObjectLocator {

    /**
     * 允许的 object logical path 字符集合。
     *
     * <p>保留 `/` 是为了支持按 run/session/command 分层；拒绝空格、反斜杠、冒号和控制字符，
     * 是为了避免 Windows 路径、URL、对象存储定位或日志注入混入。</p>
     */
    private static final Pattern SAFE_LOGICAL_PATH_PATTERN =
            Pattern.compile("^[A-Za-z0-9._/-]{1,512}$");

    private final AgentArtifactObjectStoreMinioProperties properties;

    public AgentToolActionArtifactMinioObjectLocator(AgentArtifactObjectStoreMinioProperties properties) {
        this.properties = properties;
    }

    /**
     * 将低敏 artifactReference 解析为 MinIO objectName。
     *
     * @param artifactReference worker receipt 中登记的低敏引用。
     * @return 仅供 MinIO SDK 使用的 objectName；不能写入响应、事件或日志。
     */
    public String resolveObjectName(String artifactReference) {
        String reference = requireSafeText(artifactReference, "artifactReference");
        Map.Entry<String, String> mapping = resolveMapping(reference);
        String logicalPath = reference.substring(mapping.getKey().length());
        validateLogicalPath(logicalPath);

        String rootPrefix = normalizePrefix(properties.getObjectKeyRootPrefix());
        String mappedPrefix = normalizePrefix(mapping.getValue());
        return joinObjectName(rootPrefix, mappedPrefix, logicalPath);
    }

    /**
     * 选择最长前缀匹配，支持后续添加更具体的引用前缀。
     */
    private Map.Entry<String, String> resolveMapping(String reference) {
        String lowerReference = reference.toLowerCase(Locale.ROOT);
        return properties.getReferencePrefixObjectKeyPrefixes().entrySet().stream()
                .filter(entry -> lowerReference.startsWith(entry.getKey().toLowerCase(Locale.ROOT)))
                .max(Comparator.comparingInt(entry -> entry.getKey().length()))
                .orElseThrow(() -> new IllegalArgumentException("artifactReference 未配置 MinIO objectName 映射前缀"));
    }

    /**
     * 校验 logical path 不包含路径逃逸、URL、bucket/key 明文或平台路径形态。
     */
    private void validateLogicalPath(String logicalPath) {
        String value = requireSafeText(logicalPath, "artifact logical path");
        String lower = value.toLowerCase(Locale.ROOT);
        if (!SAFE_LOGICAL_PATH_PATTERN.matcher(value).matches()
                || value.startsWith("/")
                || value.endsWith("/")
                || value.contains("//")
                || lower.contains("../")
                || lower.contains("..\\")
                || lower.contains("http://")
                || lower.contains("https://")
                || lower.contains("bucket")
                || lower.contains("object-key")
                || lower.contains("object_key")
                || lower.matches("^[a-z]:\\\\.*")
                || lower.startsWith("\\\\")) {
            throw new IllegalArgumentException("artifactReference logical path 不符合受控 objectName 映射规则");
        }
    }

    private String joinObjectName(String rootPrefix, String mappedPrefix, String logicalPath) {
        StringBuilder builder = new StringBuilder();
        appendPathPart(builder, rootPrefix);
        appendPathPart(builder, mappedPrefix);
        appendPathPart(builder, logicalPath);
        return builder.toString();
    }

    private void appendPathPart(StringBuilder builder, String value) {
        String text = normalizePrefix(value);
        if (text.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append('/');
        }
        builder.append(text);
    }

    private String normalizePrefix(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.trim().replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.contains("../") || normalized.contains("//")) {
            throw new IllegalArgumentException("MinIO objectName 前缀不能包含路径逃逸或连续分隔符");
        }
        return normalized;
    }

    private String requireSafeText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
        return value.trim();
    }
}
