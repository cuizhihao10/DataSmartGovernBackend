/**
 * @Author : Cui
 * @Date: 2026/04/27 21:30
 * @Description DataSmart Govern Backend - QualityAnomalyAggregationItem.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.controller.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 质量异常聚合结果项。
 *
 * <p>这个对象不是数据库表实体，而是面向运营分析接口的响应模型。
 * 它用于表达“某个聚合键下面有多少异常，最近一次异常发生在什么时候”。
 * 例如：
 * 1. groupBy=FIELD 时，aggregateKey 可能是 email，anomalyCount 表示 email 字段异常数量；
 * 2. groupBy=TYPE 时，aggregateKey 可能是 NULL_VALUE，anomalyCount 表示空值异常数量；
 * 3. groupBy=SEVERITY 时，aggregateKey 可能是 CRITICAL，anomalyCount 表示严重异常数量。
 */
@Data
public class QualityAnomalyAggregationItem {

    /**
     * 聚合键。
     *
     * <p>含义取决于请求的 groupBy 维度，可能是字段名、异常类型、严重级别或检测目标。
     */
    private String aggregateKey;

    /**
     * 异常数量。
     *
     * <p>用于排序、运营看板展示和后续告警阈值判断。
     */
    private Long anomalyCount;

    /**
     * 最近一次异常创建时间。
     *
     * <p>仅有数量不足以判断问题是否仍在发生，因此同时返回最近时间，
     * 方便判断异常是历史积压还是正在持续产生。
     */
    private LocalDateTime latestCreateTime;
}
