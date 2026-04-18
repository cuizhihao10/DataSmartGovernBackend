package com.czh.datasmart.govern.datasource.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * @Author : Cui
 * @Date: 2026/4/18 22:50
 * @Description DataSmart Govern Backend - IndexMetadataSummary.java
 * @Version:1.0.0
 *
 * 索引元数据摘要。
 * 索引信息对于数据同步和治理都很重要，因为它直接影响：
 * - 全量扫描性能；
 * - 增量字段选择；
 * - 分页抽取方案；
 * - 并行分片是否具备可行性。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IndexMetadataSummary {

    /**
     * 索引名称。
     */
    private String indexName;

    /**
     * 是否唯一索引。
     */
    private boolean unique;

    /**
     * 索引包含的字段名列表。
     */
    private List<String> columnNames;
}
