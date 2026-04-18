package com.czh.datasmart.govern.quality.support;

import java.util.Arrays;

/**
 * 规则严重级别。
 */
public enum QualitySeverity {
    HIGH,
    MEDIUM,
    LOW;

    public static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return MEDIUM.name();
        }
        return Arrays.stream(values())
                .map(Enum::name)
                .filter(item -> item.equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unsupported quality severity: " + value));
    }
}
