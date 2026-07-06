/**
 * @Author : Cui
 * @Date: 2026/07/07 23:32
 * @Description DataSmart Govern Backend - DatasourcePartitionRangeProbeResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.integration.datasource.partition;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * datasource-management 分片范围探测响应镜像。
 *
 * <p>minValue/maxValue 只允许在 data-sync 控制面内部用于生成结构化 range filter。
 * 不应出现在普通 worker plan、receipt、runtime event、公开日志或 current-repo-state 中。</p>
 */
@Getter
@Setter
public class DatasourcePartitionRangeProbeResponse {

    private String probeStatus;
    private Long minValue;
    private Long maxValue;
    private Long rowCount;
    private Boolean numericRange;
    private List<String> warnings;
    private String payloadPolicy;
}
