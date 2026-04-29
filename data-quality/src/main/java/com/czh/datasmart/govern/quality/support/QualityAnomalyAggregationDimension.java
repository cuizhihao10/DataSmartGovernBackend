/**
 * @Author : Cui
 * @Date: 2026/04/27 21:30
 * @Description DataSmart Govern Backend - QualityAnomalyAggregationDimension.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.support;

/**
 * 质量异常聚合维度。
 *
 * <p>异常明细进入数据库后，真实运营人员很少只是一条条看明细。
 * 更常见的问题是：
 * 1. 哪个字段异常最多；
 * 2. 哪类异常最频繁；
 * 3. 哪些严重级别的问题正在堆积；
 * 4. 哪个业务对象或表近期质量最差。
 *
 * <p>这个枚举把“业务维度”映射到数据库列名。服务层只允许使用这里声明过的列，
 * 不允许前端把任意字符串作为 group by 字段直接传给 SQL。
 * 这样既保留了聚合查询能力，也避免了动态 SQL 注入风险。
 */
public enum QualityAnomalyAggregationDimension {

    /**
     * 按异常字段聚合。
     *
     * <p>适合回答“哪个字段质量最差”，后续可以用于字段画像、数据字典质量评分和清洗优先级排序。
     */
    FIELD("FIELD", "field_name"),

    /**
     * 按异常类型聚合。
     *
     * <p>适合回答“空值、重复值、越界值、格式错误中哪类问题最多”。
     */
    TYPE("TYPE", "anomaly_type"),

    /**
     * 按严重级别聚合。
     *
     * <p>适合质量运营台判断当前异常积压是否以 CRITICAL/HIGH 为主，从而决定是否触发告警或升级处理。
     */
    SEVERITY("SEVERITY", "severity"),

    /**
     * 按检测目标聚合。
     *
     * <p>适合回答“哪张表、哪个指标或哪个业务对象最容易出问题”。
     */
    TARGET_OBJECT("TARGET_OBJECT", "target_object");

    private final String value;
    private final String columnName;

    QualityAnomalyAggregationDimension(String value, String columnName) {
        this.value = value;
        this.columnName = columnName;
    }

    /**
     * 把外部传入的维度编码转换为枚举。
     *
     * <p>如果调用方不传，默认按 FIELD 聚合。这个默认值贴近质量分析的常见入口：
     * 先找出高频问题字段，再决定是修数据、修规则，还是推动源系统整改。
     */
    public static QualityAnomalyAggregationDimension fromValue(String value) {
        if (value == null || value.isBlank()) {
            return FIELD;
        }
        for (QualityAnomalyAggregationDimension dimension : values()) {
            if (dimension.value.equalsIgnoreCase(value.trim())) {
                return dimension;
            }
        }
        throw new IllegalArgumentException("不支持的异常聚合维度: " + value);
    }

    /**
     * 返回经过白名单控制的数据库列名。
     *
     * <p>注意这个值最终会进入 MyBatis 动态 SQL 的 ${} 位置，所以绝不能来自未校验的用户输入。
     */
    public String getColumnName() {
        return columnName;
    }
}
