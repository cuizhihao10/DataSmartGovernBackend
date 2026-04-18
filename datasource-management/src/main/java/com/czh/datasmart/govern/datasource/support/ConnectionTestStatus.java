package com.czh.datasmart.govern.datasource.support;

/**
 * @Author : Cui
 * @Date: 2026/4/18 21:55
 * @Description DataSmart Govern Backend - ConnectionTestStatus.java
 * @Version:1.0.0
 *
 * 连接测试状态常量。
 * 它表达的是“最近一次连接测试结果”，和数据源自身启停状态是两个不同维度。
 */
public final class ConnectionTestStatus {

    /**
     * 尚未测试。
     */
    public static final String UNKNOWN = "UNKNOWN";

    /**
     * 测试成功。
     */
    public static final String SUCCESS = "SUCCESS";

    /**
     * 测试失败。
     */
    public static final String FAILED = "FAILED";

    private ConnectionTestStatus() {
    }
}
