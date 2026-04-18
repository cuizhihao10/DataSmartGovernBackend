package com.czh.datasmart.govern.datasource.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * @Author : Cui
 * @Date: 2026/4/18 21:55
 * @Description DataSmart Govern Backend - DataSourceConnectionTestResult.java
 * @Version:1.0.0
 *
 * 连接测试结果对象。
 * 这个对象不落库，主要用于接口返回和服务内部表达一次测试动作的结果。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DataSourceConnectionTestResult {

    /**
     * 被测试的数据源 ID。
     */
    private Long datasourceId;

    /**
     * 测试状态。
     */
    private String testStatus;

    /**
     * 测试说明消息。
     */
    private String message;

    /**
     * 测试时间。
     */
    private LocalDateTime testedAt;
}
