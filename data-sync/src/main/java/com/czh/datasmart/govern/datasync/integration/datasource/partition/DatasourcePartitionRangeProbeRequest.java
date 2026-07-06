/**
 * @Author : Cui
 * @Date: 2026/07/07 23:31
 * @Description DataSmart Govern Backend - DatasourcePartitionRangeProbeRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.integration.datasource.partition;

import lombok.Getter;
import lombok.Setter;

/**
 * data-sync 侧的 datasource-management 分片范围探测请求镜像。
 *
 * <p>该类不复用 datasource-management 的 Java DTO，是为了保持两个微服务编译解耦。字段名与远端 JSON
 * 合同保持一致，由 HTTP client 序列化后发送到 {@code /internal/sync-partitions/range-probe}。</p>
 */
@Getter
@Setter
public class DatasourcePartitionRangeProbeRequest {

    private Long datasourceId;
    private String connectorType;
    private String objectLocator;
    private String splitPk;
}
