/**
 * @Author : Cui
 * @Date: 2026/08/13 14:35
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryFactsVerifier.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 在评估 data-sync 策略前校验受限的重试事实投影。
 *
 * <p>该映射由 Agent Runtime 提供，因而本身不能成为权威依据。此帮助类只检查传输事实是否自洽。
 * 随后 {@link SyncAutopilotRecoveryCaseService} 会针对数据库持有的 execution、对象和错误持久账本，
 * 对相同条件进行第二次事实校验。</p>
 */
public final class SyncAutopilotRecoveryFactsVerifier {

    private static final String TRANSIENT_FAILURE_CLASS = "TRANSIENT_CONNECTOR_OR_WORKER";
    private static final Pattern SAFE_CODE = Pattern.compile("[A-Z0-9_.:-]{1,96}");
    private static final Set<String> NON_TRANSIENT_CODES = Set.of(
            "TARGET_DUPLICATE_KEY", "TARGET_NOT_NULL_VIOLATION", "SCHEMA_COLUMN_MISMATCH",
            "TARGET_COLUMN_TOO_NARROW", "TYPE_OR_FORMAT_CONVERSION_FAILED",
            "DATASOURCE_PERMISSION_DENIED", "DATASOURCE_CREDENTIAL_INVALID",
            "DATA_CONTRACT_VIOLATION", "SCOPE_MISMATCH");

    private SyncAutopilotRecoveryFactsVerifier() {
    }

    /**
     * 只检查 Python 投影的结构和内部一致性。
     *
     * @param facts 低敏且不可信的传输映射
     * @return 映射是可信的瞬态重试投影时返回 {@code true}
     */
    public static boolean eligibleForAutomaticRetry(Map<String, Object> facts) {
        if (facts == null || !TRANSIENT_FAILURE_CLASS.equals(code(facts.get("failureClass")))) {
            return false;
        }
        if (!Boolean.TRUE.equals(facts.get("retryable"))
                || !Boolean.TRUE.equals(facts.get("eligibleForAutomaticRetry"))) {
            return false;
        }
        int failedObjectCount = positiveInt(facts.get("failedObjectCount"));
        if (failedObjectCount <= 0 || failedObjectCount > 1_000_000) {
            return false;
        }
        Object rawRootCauses = facts.get("rootCauseCodes");
        if (!(rawRootCauses instanceof Collection<?> causes) || causes.isEmpty()) {
            return false;
        }
        boolean transientCause = false;
        for (Object rawCause : causes) {
            String cause = code(rawCause);
            if (cause.isBlank() || !SAFE_CODE.matcher(cause).matches()
                    || NON_TRANSIENT_CODES.contains(cause)) {
                return false;
            }
            if (cause.contains("CONNECTOR") || cause.contains("NETWORK") || cause.contains("WORKER")
                    || cause.contains("TIMEOUT") || cause.contains("UNAVAILABLE")) {
                transientCause = true;
            }
        }
        return transientCause;
    }

    /** 规范化一个低敏代码，以便在 Python 和 Java 之间比较。 */
    private static String code(Object value) {
        return value == null ? "" : String.valueOf(value).trim().toUpperCase(Locale.ROOT).replace('-', '_');
    }

    /** 解析一个正的受限计数器，不接受布尔值或自由格式数字文本。 */
    private static int positiveInt(Object value) {
        if (value instanceof Boolean || value == null) {
            return 0;
        }
        try {
            int result = Integer.parseInt(String.valueOf(value));
            return result > 0 ? result : 0;
        } catch (NumberFormatException exception) {
            return 0;
        }
    }
}
