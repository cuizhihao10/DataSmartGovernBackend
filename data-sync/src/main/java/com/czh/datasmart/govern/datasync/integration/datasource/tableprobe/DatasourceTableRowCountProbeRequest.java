/**
 * @Author : Cui
 * @Date: 2026/07/09 22:42
 * @Description DataSmart Govern Backend - DatasourceTableRowCountProbeRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.integration.datasource.tableprobe;

import lombok.Getter;
import lombok.Setter;

/**
 * data-sync 侧的 datasource-management 表行数探测请求镜像。
 *
 * <p>该类不直接复用 datasource-management 模块 DTO，是为了保持微服务编译解耦。
 * 字段名与远端 JSON 合同保持一致，由 HTTP 客户端序列化后发送到
 * {@code /internal/sync-tables/row-count-probe}。</p>
 */
@Getter
@Setter
public class DatasourceTableRowCountProbeRequest {

    private Long datasourceId;
    private String connectorType;
    private String objectLocator;
    private String purpose;
}
