/**
 * @Author : Cui
 * @Date: 2026/04/27 21:25
 * @Description DataSmart Govern Backend - QualityCheckExecutionState.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.support;

/**
 * 质量检测执行记录状态。
 *
 * <p>QualityCheckReport 表示检测结果，QualityCheckExecutionState 表示一次检测动作的执行状态。
 * 两者不要混用：
 * - 报告结果回答“规则是否通过”；
 * - 执行状态回答“这次检测动作有没有成功跑完”。
 */
public final class QualityCheckExecutionState {

    /**
     * 检测执行中。
     */
    public static final String RUNNING = "RUNNING";

    /**
     * 检测执行成功，并且已经生成报告。
     */
    public static final String SUCCESS = "SUCCESS";

    /**
     * 检测执行失败，例如规则不存在、规则未启用、观测值异常或后续真实数据扫描失败。
     */
    public static final String FAILED = "FAILED";

    private QualityCheckExecutionState() {
    }
}
