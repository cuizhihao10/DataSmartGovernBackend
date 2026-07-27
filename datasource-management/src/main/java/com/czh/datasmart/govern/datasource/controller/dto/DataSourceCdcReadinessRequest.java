/**
 * @Author : Cui
 * @Date: 2026/07/27 20:00
 * @Description DataSmart Govern Backend - DataSourceCdcReadinessRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.controller.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * Read-only CDC admission request for one source/target datasource pair.
 *
 * <p>The request carries object names only. It deliberately does not accept SQL,
 * connector properties, credentials, Kafka topics, or Debezium configuration.
 * All server facts are read by datasource-management from registered resources.</p>
 */
@Data
public class DataSourceCdcReadinessRequest {

    @NotNull(message = "目标数据源不能为空")
    private Long targetDatasourceId;

    @Valid
    @NotEmpty(message = "实时同步至少需要一条源表到目标表映射")
    @Size(max = 100, message = "单次 CDC 准入检查最多支持 100 条对象映射")
    private List<ObjectMapping> objectMappings;

    @Data
    public static class ObjectMapping {

        private String sourceSchemaName;

        @NotBlank(message = "源表名称不能为空")
        private String sourceObjectName;

        private String targetSchemaName;

        @NotBlank(message = "目标表名称不能为空")
        private String targetObjectName;
    }
}
