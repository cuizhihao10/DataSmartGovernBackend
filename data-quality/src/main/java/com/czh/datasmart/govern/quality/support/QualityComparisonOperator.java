package com.czh.datasmart.govern.quality.support;

import java.math.BigDecimal;
import java.util.Arrays;

/**
 * 质量规则比较运算符。
 * <p>
 * 质量规则的核心就是“拿实际观测值与期望阈值做比较”，
 * 所以这里把比较逻辑显式放到枚举中，便于学习和扩展。
 */
public enum QualityComparisonOperator {
    GT {
        @Override
        public boolean matches(BigDecimal actual, BigDecimal expected) {
            return actual.compareTo(expected) > 0;
        }
    },
    GTE {
        @Override
        public boolean matches(BigDecimal actual, BigDecimal expected) {
            return actual.compareTo(expected) >= 0;
        }
    },
    LT {
        @Override
        public boolean matches(BigDecimal actual, BigDecimal expected) {
            return actual.compareTo(expected) < 0;
        }
    },
    LTE {
        @Override
        public boolean matches(BigDecimal actual, BigDecimal expected) {
            return actual.compareTo(expected) <= 0;
        }
    },
    EQ {
        @Override
        public boolean matches(BigDecimal actual, BigDecimal expected) {
            return actual.compareTo(expected) == 0;
        }
    },
    NEQ {
        @Override
        public boolean matches(BigDecimal actual, BigDecimal expected) {
            return actual.compareTo(expected) != 0;
        }
    };

    public abstract boolean matches(BigDecimal actual, BigDecimal expected);

    public static QualityComparisonOperator fromValue(String value) {
        return Arrays.stream(values())
                .filter(item -> item.name().equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unsupported comparison operator: " + value));
    }
}
