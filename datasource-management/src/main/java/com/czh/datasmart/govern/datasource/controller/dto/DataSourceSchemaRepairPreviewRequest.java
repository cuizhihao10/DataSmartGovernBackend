package com.czh.datasmart.govern.datasource.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** Request for a non-mutating schema repair preview. */
@Data
public class DataSourceSchemaRepairPreviewRequest {

    @NotBlank
    private String operation;

    @Size(max = 128)
    private String schemaName;

    @NotBlank
    @Size(max = 128)
    private String tableName;

    @Size(max = 128)
    private String columnName;

    @Size(max = 32)
    private String requestedType;

    private Integer requestedLength;

    /**
     * CREATE_TABLE 专用的白名单字段定义。其他结构修复操作忽略该字段。
     * 请求只描述结构事实，不允许提交默认值表达式、原始 DDL、触发器或索引 SQL。
     */
    @Valid
    @Size(max = 160)
    private List<CreateTableColumn> columns;

    @Data
    public static class CreateTableColumn {
        @NotBlank
        @Size(max = 128)
        private String columnName;

        @NotBlank
        @Size(max = 32)
        private String dataType;

        /** VARCHAR length. Ignored for non-character types. */
        private Integer length;

        /** DECIMAL precision. */
        private Integer precision;

        /** DECIMAL scale. */
        private Integer scale;

        private boolean nullable = true;
        private boolean primaryKey;
    }
}
