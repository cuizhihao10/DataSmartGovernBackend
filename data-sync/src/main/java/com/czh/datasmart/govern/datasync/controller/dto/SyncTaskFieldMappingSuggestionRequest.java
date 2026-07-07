/**
 * @Author : Cui
 * @Date: 2026/07/07 22:50
 * @Description DataSmart Govern Backend - SyncTaskFieldMappingSuggestionRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 字段映射建议请求。
 *
 * <p>创建同步任务时，用户选择源表和目标表后，前端可以调用该接口自动获取两端字段清单并生成初始映射。
 * 自动映射采用“字段名同名优先 + 简单类型族兼容判断”的保守策略：能明确匹配的字段默认勾选同步；
 * 没有目标字段或类型明显不兼容的字段默认不勾选，交给用户或 Agent 进一步确认。</p>
 */
@Data
public class SyncTaskFieldMappingSuggestionRequest {

    @NotNull(message = "源端数据源 ID 不能为空")
    private Long sourceDatasourceId;

    @NotNull(message = "目标端数据源 ID 不能为空")
    private Long targetDatasourceId;

    private String sourceConnectorType;
    private String targetConnectorType;
    private String sourceCatalog;
    private String sourceSchema;

    @NotBlank(message = "源端表名不能为空")
    private String sourceTable;

    private String targetCatalog;
    private String targetSchema;

    @NotBlank(message = "目标端表名不能为空")
    private String targetTable;

    private Integer maxColumnsPerTable;
}
