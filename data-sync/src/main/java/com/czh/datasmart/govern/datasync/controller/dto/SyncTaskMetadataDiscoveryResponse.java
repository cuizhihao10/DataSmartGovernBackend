/**
 * @Author : Cui
 * @Date: 2026/07/07 22:49
 * @Description DataSmart Govern Backend - SyncTaskMetadataDiscoveryResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import lombok.Data;

import java.util.List;

/**
 * 同步任务创建页的元数据对象发现响应。
 *
 * <p>响应只包含低敏结构信息：schema、表名、表类型、主键和字段类型，不包含样本行、连接串、账号、密码或完整 SQL。
 * 前端可直接使用 {@link #schemas} 渲染 schema 下拉框，使用 {@link #tables} 渲染待同步对象列表。</p>
 */
@Data
public class SyncTaskMetadataDiscoveryResponse {

    private Long datasourceId;
    private String side;
    private String connectorType;
    private String filterMode;
    private Boolean discoverable;
    private List<String> schemas;
    private List<TableObject> tables;
    private List<String> warnings;

    @Data
    public static class TableObject {
        private String catalog;
        private String schemaName;
        private String tableName;
        private String tableType;
        private List<String> primaryKeys;
        private List<FieldObject> fields;
    }

    @Data
    public static class FieldObject {
        private String fieldName;
        private String dataTypeName;
        private Boolean nullable;
        private Boolean primaryKey;
        private Integer ordinalPosition;
        private Boolean syncEnabled;
    }
}
