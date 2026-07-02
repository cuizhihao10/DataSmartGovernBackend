/**
 * @Author : Cui
 * @Date: 2026/07/02 03:10
 * @Description DataSmart Govern Backend - QualityRemediationSubmissionValueSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.tool;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;

/**
 * 质量整改任务提交链路的低敏值解析支持。
 *
 * <p>data-quality 返回体和历史 outbox payload 可能包含字符串、数字或布尔值的不同 JSON 表达。该类统一
 * 做保守转换：无法解析的标识返回 null，无法解析的数量返回调用方默认值，错误消息强制截断，防止下游
 * 异常正文、SQL、样本或内部 endpoint 被原样扩散到 Agent 审计和 API 响应。
 */
final class QualityRemediationSubmissionValueSupport {

    private QualityRemediationSubmissionValueSupport() {
    }

    static PlatformBusinessException badRequest(String message) {
        return new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST, message);
    }

    static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw badRequest(fieldName + " 不能为空");
        }
        return value.trim();
    }

    static String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }

    static String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    static String stringValue(Long value) {
        return value == null ? null : String.valueOf(value);
    }

    static boolean safeEquals(String left, String right) {
        String normalizedLeft = text(left);
        String normalizedRight = text(right);
        return normalizedLeft != null && normalizedLeft.equals(normalizedRight);
    }

    static Boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    static Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception ignored) {
            return null;
        }
    }

    static int intValue(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    static String safeMessage(Object value, String fallback) {
        String parsed = text(value);
        if (parsed == null) {
            return fallback;
        }
        return parsed.length() <= 300 ? parsed : parsed.substring(0, 300);
    }

    static String safeExceptionMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.length() <= 240 ? message : message.substring(0, 240);
    }
}
