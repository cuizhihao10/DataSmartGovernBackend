/**
 * @Author : Cui
 * @Date: 2026/07/07 22:48
 * @Description DataSmart Govern Backend - SyncTaskMetadataDiscoveryRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 同步任务创建页的元数据对象发现请求。
 *
 * <p>该请求是 data-sync 面向前端的“同步配置语义”接口，而不是 datasource-management 的裸元数据接口。
 * 前端选择源端/目标端数据源后，可以通过该接口自动获取 schema、表和字段摘要；data-sync 会根据连接器类型处理
 * MySQL、PostgreSQL 等数据库在 catalog/schema/table 语义上的差异。</p>
 */
@Data
public class SyncTaskMetadataDiscoveryRequest {

    /**
     * 数据源 ID。
     */
    @NotNull(message = "数据源 ID 不能为空")
    private Long datasourceId;

    /**
     * 数据源所在侧，常见值为 SOURCE 或 TARGET。
     *
     * <p>该字段主要用于响应提示和审计语义，不参与远端数据读取。</p>
     */
    @Size(max = 16, message = "数据源侧标识不能超过 16 个字符")
    private String side;

    /**
     * 连接器类型。
     *
     * <p>可选。为空时 data-sync 会读取 datasource-management 的低敏能力快照补全；如果传入，会用于判断不同数据库筛选模式。</p>
     */
    @Size(max = 64, message = "连接器类型不能超过 64 个字符")
    private String connectorType;

    /**
     * 筛选模式。
     *
     * <p>支持 TABLE、SCHEMA、SCHEMA_AND_TABLE、CATALOG、ALL。MySQL 没有 PostgreSQL 风格 schema，
     * 因此 SCHEMA/SCHEMA_AND_TABLE 在 MySQL 下会返回空列表和明确提示，TABLE 模式则可以正常列出 MySQL 表。</p>
     */
    @Size(max = 32, message = "筛选模式不能超过 32 个字符")
    private String filterMode;

    private String catalog;
    private String schemaPattern;
    private String tableNamePattern;
    private Boolean includeColumns;
    private Boolean includeViews;
    private Integer maxTables;
    private Integer maxColumnsPerTable;
}
