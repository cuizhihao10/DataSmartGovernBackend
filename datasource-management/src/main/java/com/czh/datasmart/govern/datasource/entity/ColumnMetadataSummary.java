package com.czh.datasmart.govern.datasource.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Author : Cui
 * @Date: 2026/4/18 22:42
 * @Description DataSmart Govern Backend - ColumnMetadataSummary.java
 * @Version:1.0.0
 *
 * 字段元数据摘要。
 * 当前阶段先返回对学习和配置最关键的信息：
 * - 字段名；
 * - 类型；
 * - 长度；
 * - 是否可空；
 * - 默认值；
 * - 精度；
 * - 是否自增；
 * - 是否主键；
 * - 备注；
 * - 在表中的顺序。
 *
 * 这些信息一旦补齐，字段级元数据就已经足以支撑第一版：
 * - 字段映射预览；
 * - 增量字段候选判断；
 * - 主键与去重策略配置；
 * - 样本展示前的列结构说明。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ColumnMetadataSummary {

    /**
     * 字段名称。
     */
    private String columnName;

    /**
     * 数据库字段类型名称。
     */
    private String dataTypeName;

    /**
     * 字段长度或显示大小。
     */
    private Integer columnSize;

    /**
     * 是否允许为空。
     */
    private boolean nullable;

    /**
     * 默认值。
     * 有些数据库驱动可能返回表达式或函数文本，因此这里只做原样摘要返回。
     */
    private String defaultValue;

    /**
     * 小数位数。
     * 对数值型字段很有帮助，便于后续做精度兼容判断。
     */
    private Integer decimalDigits;

    /**
     * 是否自动递增。
     */
    private boolean autoIncrement;

    /**
     * 是否主键字段。
     * 这个字段在字段级直接展开，可以让前端在字段清单里直接高亮，而不必再单独拼装。
     */
    private boolean primaryKey;

    /**
     * 字段备注。
     */
    private String remarks;

    /**
     * 字段顺序。
     */
    private Integer ordinalPosition;
}
