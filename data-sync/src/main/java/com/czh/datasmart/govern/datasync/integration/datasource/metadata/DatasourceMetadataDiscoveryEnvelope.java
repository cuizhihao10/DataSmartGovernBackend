/**
 * @Author : Cui
 * @Date: 2026/07/07 23:59
 * @Description DataSmart Govern Backend - DatasourceMetadataDiscoveryEnvelope.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.integration.datasource.metadata;

import lombok.Getter;
import lombok.Setter;

/**
 * datasource-management 统一响应 envelope 镜像。
 */
@Getter
@Setter
public class DatasourceMetadataDiscoveryEnvelope {

    private Integer code;
    private String message;
    private DatasourceMetadataDiscoveryResponse data;
}
