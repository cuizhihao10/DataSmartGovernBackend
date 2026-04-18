package com.czh.datasmart.govern.datasource.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 连接测试结果对象。
 * <p>
 * 这个对象不落库，主要用于接口返回和服务内部表达一次测试的结果。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DataSourceConnectionTestResult {

    private Long datasourceId;

    private String testStatus;

    private String message;

    private LocalDateTime testedAt;
}
