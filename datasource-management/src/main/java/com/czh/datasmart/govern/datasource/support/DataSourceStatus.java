package com.czh.datasmart.govern.datasource.support;

/**
 * @Author : Cui
 * @Date: 2026/4/18 21:55
 * @Description DataSmart Govern Backend - DataSourceStatus.java
 * @Version:1.0.0
 *
 * 数据源状态常量。
 * 当前阶段聚焦的是“这条配置是否允许被平台使用”：
 * - ACTIVE：已启用，可以被其他模块使用。
 * - INACTIVE：已停用，但配置仍然保留。
 * - DELETED：逻辑删除，不再参与常规业务。
 */
public final class DataSourceStatus {

    /**
     * 已启用。
     */
    public static final String ACTIVE = "ACTIVE";

    /**
     * 已停用。
     */
    public static final String INACTIVE = "INACTIVE";

    /**
     * 已逻辑删除。
     */
    public static final String DELETED = "DELETED";

    /**
     * Normalize a list-query status without leaking the persistence vocabulary into every client.
     *
     * <p>The datasource table uses {@code ACTIVE}/{@code INACTIVE}, while the console domain model uses
     * {@code ENABLED}/{@code DISABLED}. Accepting both pairs at this API boundary keeps filtering consistent with
     * response normalization and prevents a visible datasource from disappearing only because a selector applies
     * the console-facing status name.</p>
     *
     * @param value status supplied by a list client
     * @return the canonical persistence status
     * @throws IllegalArgumentException when the value is not a supported datasource lifecycle status
     */
    public static String normalizeQueryValue(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Datasource status must not be blank");
        }
        return switch (value.trim().toUpperCase()) {
            case ACTIVE, "ENABLED" -> ACTIVE;
            case INACTIVE, "DISABLED" -> INACTIVE;
            case DELETED -> DELETED;
            default -> throw new IllegalArgumentException("Unsupported datasource status: " + value);
        };
    }

    private DataSourceStatus() {
    }
}
