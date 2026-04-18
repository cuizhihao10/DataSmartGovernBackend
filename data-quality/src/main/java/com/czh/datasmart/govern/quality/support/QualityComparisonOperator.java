/**
 * @Author : Cui
 * @Date: 2026/4/18 21:35
 * @Description DataSmart Govern Backend - QualityComparisonOperator.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.support;

import java.math.BigDecimal;
import java.util.Arrays;

/**
 * 质量比较运算符枚举。
 * 数据质量规则执行的本质，就是把“实际观测值”和“期望值”做一次确定性比较。
 * 因此把比较逻辑集中到枚举中，比散落在 Service 里的 if/else 更清晰。
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

    /**
     * 执行实际比较。
     */
    public abstract boolean matches(BigDecimal actual, BigDecimal expected);

    /**
     * 将外部传入字符串解析为运算符枚举。
     * 这里统一处理大小写兼容，避免业务层重复写归一化逻辑。
     */
    public static QualityComparisonOperator fromValue(String value) {
        return Arrays.stream(values())
                .filter(item -> item.name().equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的比较运算符: " + value));
    }
}
