/**
 * @Author : Cui
 * @Date: 2026/07/02 03:20
 * @Description DataSmart Govern Backend - AgentToolActionCommandProposalValueSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * 工具动作命令提案的规范化与摘要支持。
 *
 * <p>这些方法只处理低敏代码、schemaVersion 和 payloadReference，不读取 payload 正文。统一 trim、空列表
 * 保护和 SHA-256 可以避免同一证据因空格或 null 表达差异生成不同幂等关联。
 */
final class AgentToolActionCommandProposalValueSupport {

    private AgentToolActionCommandProposalValueSupport() {
    }

    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 缺少 SHA-256，无法生成工具动作 command proposal ID", exception);
        }
    }

    static void addIfText(Set<String> target, String value) {
        String text = safeText(value);
        if (text != null) {
            target.add(text);
        }
    }

    static String defaultText(String value, String fallback) {
        String text = safeText(value);
        return text == null ? fallback : text;
    }

    static String safeText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }
}
