/**
 * @Author : Cui
 * @Date: 2026/07/02 04:10
 * @Description DataSmart Govern Backend - AgentAsyncToolPreCheckValueSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 异步 Agent 工具执行前复核的低敏文本支持。
 *
 * <p>该类只做证据码规范化、集合比较和异常消息截断，不读取 payload 正文、不调用权限中心，也不决定是否执行。
 */
final class AgentAsyncToolPreCheckValueSupport {

    private AgentAsyncToolPreCheckValueSupport() {
    }

    static boolean looksLikeSensitivePayload(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("select ") || lower.contains("insert ") || lower.contains("authorization:")
                || lower.contains("bearer ") || lower.contains("password") || lower.contains("prompt:");
    }

    static boolean sameText(String left, String right) {
        String normalizedLeft = optionalText(left);
        String normalizedRight = optionalText(right);
        return normalizedLeft != null && normalizedLeft.equals(normalizedRight);
    }

    static boolean containsNormalized(List<String> values, String expected) {
        String normalizedExpected = optionalText(expected);
        return normalizedExpected != null && normalizedTextSet(values).contains(normalizedExpected);
    }

    static Set<String> normalizedTextSet(List<String> values) {
        Set<String> normalized = new LinkedHashSet<>();
        if (values == null) {
            return normalized;
        }
        for (String value : values) {
            String item = optionalText(value);
            if (item != null) {
                normalized.add(item);
            }
        }
        return normalized;
    }

    static String safeExceptionMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        String trimmed = message.trim();
        return trimmed.length() <= 300 ? trimmed : trimmed.substring(0, 300);
    }

    static String optionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static String firstText(List<String> values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            String item = optionalText(value);
            if (item != null) {
                return item;
            }
        }
        return null;
    }

    static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
