/**
 * @Author : Cui
 * @Date: 2026/07/07 22:51
 * @Description DataSmart Govern Backend - SyncTaskFieldMappingSuggestionResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import lombok.Data;

import java.util.List;

/**
 * 字段映射建议响应。
 *
 * <p>该响应面向前端字段映射表格：每一行代表一个源字段及其建议目标字段。
 * {@link FieldMappingItem#syncEnabled} 是默认勾选状态，前端仍应允许用户手动取消或修改目标字段。</p>
 */
@Data
public class SyncTaskFieldMappingSuggestionResponse {

    private Long sourceDatasourceId;
    private Long targetDatasourceId;
    private String sourceConnectorType;
    private String targetConnectorType;
    private String sourceTable;
    private String targetTable;
    private List<FieldMappingItem> mappings;
    private List<String> warnings;

    @Data
    public static class FieldMappingItem {
        private String sourceField;
        private String sourceType;
        private String targetField;
        private String targetType;
        private Boolean syncEnabled;
        private Boolean typeCompatible;
        private Boolean primaryKey;
        private Boolean nullable;
        private String compatibilityNote;
    }
}
