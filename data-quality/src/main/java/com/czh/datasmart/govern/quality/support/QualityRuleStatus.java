/**
 * @Author : Cui
 * @Date: 2026/4/18 21:35
 * @Description DataSmart Govern Backend - QualityRuleStatus.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.support;

/**
 * 质量规则状态常量。
 *
 * <p>质量规则不是创建后就永远处于可执行状态。
 * 真实产品中，一条规则通常会经历草稿、发布、停用、归档、删除等生命周期：
 * - 草稿：规则还在配置和评审中，不能执行。
 * - 启用：规则已经生效，可以被任务、调度器或人工触发执行。
 * - 停用：规则暂时不执行，但仍可能恢复。
 * - 归档：规则不再用于当前治理，但保留历史报告和解释能力。
 * - 删除：逻辑删除，列表默认不可见。
 */
public final class QualityRuleStatus {

    /**
     * 草稿状态。
     *
     * <p>新建规则默认先进入 DRAFT，避免尚未评审的规则立刻参与生产质量检测。
     */
    public static final String DRAFT = "DRAFT";

    /**
     * 可执行状态。
     */
    public static final String ACTIVE = "ACTIVE";

    /**
     * 已停用，但仍保留规则定义。
     */
    public static final String INACTIVE = "INACTIVE";

    /**
     * 已归档。
     *
     * <p>归档规则不再执行，但与历史报告仍保留关联，适合规则下线后的审计和复盘。
     */
    public static final String ARCHIVED = "ARCHIVED";

    /**
     * 已逻辑删除。
     */
    public static final String DELETED = "DELETED";

    private QualityRuleStatus() {
    }
}
