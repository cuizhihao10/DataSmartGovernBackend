/**
 * @Author : Cui
 * @Date: 2026/04/27 22:00
 * @Description DataSmart Govern Backend - DatasourceMetadataDiscoveryRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.integration.datasource;

import lombok.Data;

/**
 * 调用 datasource-management 元数据发现接口的请求模型。
 *
 * <p>这个类刻意定义在 data-quality 模块内，而不是直接引用 datasource-management 的 DTO。
 * 这样可以保持微服务边界：两个服务通过 HTTP JSON 契约交互，而不是在编译期互相依赖对方内部 Java 包。
 *
 * <p>字段名与 datasource-management 的 MetadataDiscoveryRequest 对齐。
 */
@Data
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
