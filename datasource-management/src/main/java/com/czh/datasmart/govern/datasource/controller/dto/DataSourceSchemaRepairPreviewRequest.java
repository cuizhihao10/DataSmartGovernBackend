package com.czh.datasmart.govern.datasource.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

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

    @NotBlank
    @Size(max = 128)
    private String columnName;

    @Size(max = 32)
    private String requestedType;

    private Integer requestedLength;
}
