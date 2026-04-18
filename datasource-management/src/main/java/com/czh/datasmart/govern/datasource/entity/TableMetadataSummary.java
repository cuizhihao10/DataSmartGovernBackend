package com.czh.datasmart.govern.datasource.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * @Author : Cui
 * @Date: 2026/4/18 22:42
 * @Description DataSmart Govern Backend - TableMetadataSummary.java
 * @Version:1.0.0
 *
 * 表级元数据摘要。
 * 这个对象既服务于前端元数据浏览，也服务于未来同步模板创建页中的“源表选择”和“字段预览”。
 * 随着本轮扩展，它开始同时承载：
 * - 主键概览；
 * - 索引概览；
 * - 样本预览；
 * - 字段截断提示。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TableMetadataSummary {

    /**
     * catalog 名称。
     */
    private String catalog;

    /**
     * schema 名称。
     */
    private String schemaName;

    /**
     * 表名。
     */
    private String tableName;

    /**
     * 表类型，例如 TABLE、VIEW。
     */
    private String tableType;

    /**
     * 备注信息。
     */
    private String remarks;

    /**
     * 字段数量。
     * 这里表示当前返回结果中的字段数量，而不一定等于表真实总字段数。
     */
    private Integer columnCount;

    /**
     * 表真实字段总数。
     * 当接口启用了“每表最多返回 N 个字段”的限制时，这个值能帮助前端区分“已完整返回”还是“只返回了部分字段”。
     */
    private Integer totalColumnCount;

    /**
     * 当前字段结果是否被截断。
     */
    private Boolean columnsTruncated;

    /**
     * 主键字段列表。
     */
    private List<String> primaryKeys;

    /**
     * 索引摘要列表。
     */
    private List<IndexMetadataSummary> indexes;

    /**
     * 字段清单。
     * 当前支持按请求参数决定是否返回，以平衡接口信息量与响应大小。
     */
    private List<ColumnMetadataSummary> columns;

    /**
     * 样本数据预览。
     * 当前只在明确开启时才返回，因为这类能力虽然对学习和配置很有帮助，但也更容易引发性能和敏感数据风险。
     */
    private List<SampleRowPreview> sampleRows;
}
