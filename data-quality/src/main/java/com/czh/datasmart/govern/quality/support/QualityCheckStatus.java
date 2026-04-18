/**
 * @Author : Cui
 * @Date: 2026/4/18 21:35
 * @Description DataSmart Govern Backend - QualityCheckStatus.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.support;

/**
 * 质量检测结果状态常量。
 * 这里表达的是“一次执行结果”的状态，而不是规则自身的生命周期状态。
 */
public final class QualityCheckStatus {

    /**
     * 检测通过。
     */
    public static final String PASSED = "PASSED";

    /**
     * 检测失败。
     */
    public static final String FAILED = "FAILED";

    private QualityCheckStatus() {
    }
}
