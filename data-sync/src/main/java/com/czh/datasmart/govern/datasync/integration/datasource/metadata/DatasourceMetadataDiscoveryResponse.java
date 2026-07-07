/**
 * @Author : Cui
 * @Date: 2026/07/07 23:59
 * @Description DataSmart Govern Backend - DatasourceMetadataDiscoveryResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.integration.datasource.metadata;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * data-sync 侧元数据发现响应镜像。
 *
 * <p>这里只保留 SCHEMA_FULL/DATABASE_FULL fan-out 必需的低敏字段：表名、schema、表类型、主键和字段清单。
 * 不镜像样本数据、索引详情等字段，是为了避免 data-sync 在执行调度阶段持有不必要的业务值。</p>
 */
@Getter
@Setter
public class DatasourceMetadataDiscoveryResponse {

    private Long datasourceId;
    private String datasourceType;
    private Integer tableCount;
    private List<TableSummary> tables;
    private List<String> warnings;

    @Getter
    @Setter
    public static class TableSummary {
        private String catalog;
        private String schemaName;
        private String tableName;
        private String tableType;
        private List<String> primaryKeys;
        private List<ColumnSummary> columns;
    }

    @Getter
    @Setter
    public static class ColumnSummary {
        private String columnName;
        private String dataTypeName;
        private boolean nullable;
        private boolean primaryKey;
        private Integer ordinalPosition;
    }
}
