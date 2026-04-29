/**
 * @Author : Cui
 * @Date: 2026/04/27 21:50
 * @Description DataSmart Govern Backend - QualityRuleTargetType.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.support;

import java.util.Arrays;

/**
 * 质量规则检测目标类型。
 *
 * <p>早期实现只用 targetObject 一个字符串表示检测目标，这种方式足够打通 demo，
 * 但不足以支撑真实商业产品。因为“表字段”“Kafka Topic”“文件路径”“API 响应”
 * 的可检测性判断、采样方式、失败原因和性能成本完全不同。
 *
 * <p>这个枚举用于把检测目标分层建模：
 * 1. GENERIC：兼容历史字符串目标或人工指标；
 * 2. RELATIONAL_TABLE / RELATIONAL_FIELD：面向 MySQL、PostgreSQL 等关系型数据；
 * 3. KAFKA_TOPIC：面向流式消息或 CDC 数据；
 * 4. FILE_OBJECT：面向 CSV、Excel、Parquet、对象存储文件；
 * 5. API_ENDPOINT：面向外部接口响应质量检测。
 */
public enum QualityRuleTargetType {

    /**
     * 通用目标。
     *
     * <p>用于兼容历史数据和人工指标规则。它不会假设存在具体数据源连接器。
     */
    GENERIC,

    /**
     * 关系型表。
     *
     * <p>典型目标是 MySQL/PostgreSQL/Hive 表。表级规则常用于行数、重复率、空值率等检测。
     */
    RELATIONAL_TABLE,

    /**
     * 关系型字段。
     *
     * <p>字段级规则常用于空值、格式、范围、唯一性、枚举合法性检测。
     */
    RELATIONAL_FIELD,

    /**
     * Kafka Topic。
     *
     * <p>面向实时数据质量检测、消息结构校验、延迟和空消息检测等场景。
     */
    KAFKA_TOPIC,

    /**
     * 文件对象。
     *
     * <p>面向 CSV、Excel、JSON、Parquet、对象存储路径等离线文件质量检测。
     */
    FILE_OBJECT,

    /**
     * API 端点。
     *
     * <p>面向第三方接口、内部服务响应字段、状态码和数据结构质量检测。
     */
    API_ENDPOINT;

    /**
     * 将外部输入归一化为目标类型。
     *
     * <p>如果调用方不传 targetType，默认使用 GENERIC，保证旧接口调用和旧数据不被破坏。
     */
    public static QualityRuleTargetType fromValue(String value) {
        if (value == null || value.isBlank()) {
            return GENERIC;
        }
        return Arrays.stream(values())
                .filter(item -> item.name().equalsIgnoreCase(value.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的质量规则目标类型: " + value));
    }
}
