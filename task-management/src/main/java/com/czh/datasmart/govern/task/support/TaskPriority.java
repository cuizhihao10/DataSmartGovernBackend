package com.czh.datasmart.govern.task.support;

import java.util.Arrays;

/**
 * 任务优先级枚举。
 * <p>
 * 这一层当前最大的作用是做“输入归一化”。
 * 项目早期如果不在入口把高、中、低优先级收敛成稳定值，
 * 数据库很容易积累出大小写不统一、拼写不统一的问题。
 */
public enum TaskPriority {
    HIGH,
    MEDIUM,
    LOW;

    /**
     * 把外部传入的优先级字符串标准化为枚举名。
     * <p>
     * - 未传值：默认 MEDIUM。
     * - 传入非法值：抛出异常，阻止脏数据入库。
     */
    public static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return MEDIUM.name();
        }

        return Arrays.stream(values())
                .map(Enum::name)
                .filter(item -> item.equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unsupported task priority: " + value));
    }
}
