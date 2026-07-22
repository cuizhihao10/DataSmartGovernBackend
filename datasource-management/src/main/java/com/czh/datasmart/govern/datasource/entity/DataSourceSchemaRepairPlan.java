package com.czh.datasmart.govern.datasource.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Audit ledger for a digest-bound, allow-listed datasource schema repair.
 * Raw DDL is deliberately excluded from this entity.
 */
@Data
@TableName("datasource_schema_repair_plan")
public class DataSourceSchemaRepairPlan {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String planRef;
    private Long tenantId;
    private Long projectId;
    private Long datasourceId;
    private String datasourceType;
    private String operation;
    private String schemaName;
    private String tableName;
    private String columnName;
    private String currentType;
    private Integer currentLength;
    private Boolean currentNullable;
    private String requestedType;
    private Integer requestedLength;
    private String metadataDigest;
    private String impactSummary;
    private String confirmationDigest;
    private String planStatus;
    private String failureCode;
    private Long createdBy;
    private Long appliedBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private LocalDateTime appliedAt;
}
