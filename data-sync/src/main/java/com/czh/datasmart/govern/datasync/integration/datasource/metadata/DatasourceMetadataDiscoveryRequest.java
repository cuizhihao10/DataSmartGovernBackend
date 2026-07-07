/**
 * @Author : Cui
 * @Date: 2026/07/07 23:59
 * @Description DataSmart Govern Backend - DatasourceMetadataDiscoveryRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.integration.datasource.metadata;

import lombok.Getter;
import lombok.Setter;

/**
 * data-sync 侧元数据发现请求镜像。
 *
 * <p>该类镜像 datasource-management 的 {@code MetadataDiscoveryRequest}，但不通过 Java 模块依赖共享 DTO。
 * 这样两个微服务可以独立发布，同时保持 JSON 契约足够清晰。</p>
 */
@Getter
@Setter
public class DatasourceMetadataDiscoveryRequest {

    private Long actorId;
    private String actorRole;
    private Long actorTenantId;
    private String catalog;
    private String schemaPattern;
    private String tableNamePattern;
    private Integer maxTables;
    private Integer maxColumnsPerTable;
    private Boolean includeColumns;
    private Boolean includeViews;
    private Boolean includePrimaryKeys;
    private Boolean includeIndexes;
    private Boolean includeSampleRows;
    private Integer sampleRowLimit;
}
