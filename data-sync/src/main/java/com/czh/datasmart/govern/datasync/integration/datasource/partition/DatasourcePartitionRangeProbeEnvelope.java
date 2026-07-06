/**
 * @Author : Cui
 * @Date: 2026/07/07 23:33
 * @Description DataSmart Govern Backend - DatasourcePartitionRangeProbeEnvelope.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.integration.datasource.partition;

import lombok.Getter;
import lombok.Setter;

/**
 * datasource-management range-probe 响应 envelope 的本地镜像。
 */
@Getter
@Setter
public class DatasourcePartitionRangeProbeEnvelope {

    private Integer code;
    private String message;
    private DatasourcePartitionRangeProbeResponse data;
}
