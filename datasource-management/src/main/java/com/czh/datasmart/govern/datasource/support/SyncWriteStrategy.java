package com.czh.datasmart.govern.datasource.support;

import java.util.Arrays;

/**
 * @Author : Cui
 * @Date: 2026/4/19 10:36
 * @Description DataSmart Govern Backend - SyncWriteStrategy.java
 * @Version:1.0.0
 *
 * 同步写入策略枚举。
 * 写入策略回答的是“目标端如何接纳同步过来的数据”。
 *
 * 这不是一个仅供前端展示的字段，而是会直接影响：
 * 1. 目标端是否必须具备可用于冲突判定的唯一约束；
 * 2. 回放、补数、重试时是否容易产生重复数据；
 * 3. 平台是否需要提示高风险操作，如覆盖写入；
 * 4. 执行器后续应采用什么 SQL 或 connector 写入语义。
 *
 * 当前阶段先保留最常见的五种策略：
 * - APPEND：只追加，不主动解决冲突。
 * - UPSERT：冲突时更新，不冲突时插入。
 * - INSERT_IGNORE：冲突时忽略。
 * - REPLACE：冲突时替换。
 * - OVERWRITE：覆盖式写入，通常意味着先清空或整体替换目标对象。
 */
public enum SyncWriteStrategy {
    APPEND(false, false),
    UPSERT(true, false),
    INSERT_IGNORE(true, false),
    REPLACE(true, false),
    OVERWRITE(false, true);

    /**
     * 当前策略是否要求目标端具备可用于冲突判定的唯一约束。
     */
    private final boolean requiresTargetUniqueConstraint;

    /**
     * 当前策略是否具有明显的覆盖式破坏风险。
     */
    private final boolean destructiveRewrite;

    SyncWriteStrategy(boolean requiresTargetUniqueConstraint, boolean destructiveRewrite) {
        this.requiresTargetUniqueConstraint = requiresTargetUniqueConstraint;
        this.destructiveRewrite = destructiveRewrite;
    }

    public boolean requiresTargetUniqueConstraint() {
        return requiresTargetUniqueConstraint;
    }

    public boolean isDestructiveRewrite() {
        return destructiveRewrite;
    }

    /**
     * 将外部输入转换为平台内部标准写入策略。
     * 为了兼容当前已有调用方式，空值默认回落到 APPEND。
     */
    public static SyncWriteStrategy fromValue(String value) {
        if (value == null || value.isBlank()) {
            return APPEND;
        }
        return Arrays.stream(values())
                .filter(item -> item.name().equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的写入策略: " + value));
    }
}
