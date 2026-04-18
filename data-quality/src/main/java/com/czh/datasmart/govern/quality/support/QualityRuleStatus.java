/**
 * @Author : Cui
 * @Date: 2026/4/18 21:35
 * @Description DataSmart Govern Backend - QualityRuleStatus.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.support;

/**
 * 质量规则状态常量。
 * 当前阶段状态集合简单且稳定，因此直接用常量类表达最直观。
 */
public final class QualityRuleStatus {

    /**
     * 可执行状态。
     */
    public static final String ACTIVE = "ACTIVE";

    /**
     * 已停用，但仍保留规则定义。
     */
    public static final String INACTIVE = "INACTIVE";

    /**
     * 已逻辑删除。
     */
    public static final String DELETED = "DELETED";

    private QualityRuleStatus() {
    }
}
