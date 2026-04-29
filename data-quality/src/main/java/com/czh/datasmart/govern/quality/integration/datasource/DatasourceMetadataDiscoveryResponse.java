/**
 * @Author : Cui
 * @Date: 2026/04/27 22:00
 * @Description DataSmart Govern Backend - DatasourceMetadataDiscoveryResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.integration.datasource;

import lombok.Data;

import java.util.List;

/**
 * datasource-management 元数据发现结果的本地契约模型。
 *
 * <p>data-quality 只关心表和字段是否存在，因此这里只复制最小必要字段。
 * 这种“局部契约”比直接复用对方完整实体更稳：即使 datasource-management 未来增加更多返回字段，
 * data-quality 也不需要跟着变化。
 */
@Data
public class DatasourceMetadataDiscoveryResponse {

    /**
     * 实际发现的表摘要。
     */
    private List<TableSummary> tables;

    /**
     * 远程发现过程中的提示信息。
     */
    private List<String> warnings;

    @Data
    public static class TableSummary {

        private String tableName;

        private String schemaName;

        private List<ColumnSummary> columns;
    }

    @Data
    public static class ColumnSummary {

        private String columnName;

        private String dataTypeName;

        private boolean nullable;
    }
}
