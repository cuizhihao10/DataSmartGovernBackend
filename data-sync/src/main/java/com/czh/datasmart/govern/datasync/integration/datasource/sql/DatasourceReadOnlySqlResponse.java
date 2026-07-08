/**
 * @Author : Cui
 * @Date: 2026/07/08 16:43
 * @Description DataSmart Govern Backend - DatasourceReadOnlySqlResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.integration.datasource.sql;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * datasource-management 只读 SQL 执行结果镜像。
 *
 * <p>data-sync 只消费其中的列名、列数、耗时、行数和 warning。
 * {@code rows} 字段保留是为了 JSON 反序列化兼容 datasource-management 的响应结构，但创建向导支持类会主动丢弃它，
 * 避免 SQL 检查入口演变成样本预览或数据导出入口。</p>
 */
@Getter
@Setter
public class DatasourceReadOnlySqlResponse {

    private Long datasourceId;
    private String datasourceName;
    private String datasourceType;
    private String purpose;
    private Boolean executed;
    private Integer returnedRowCount;
    private Integer columnCount;
    private Integer appliedMaxRows;
    private Integer appliedQueryTimeoutSeconds;
    private Long durationMs;
    private List<String> columns;
    private List<Map<String, Object>> rows;
    private List<String> warnings;
    private LocalDateTime executedAt;
    private String message;
}
