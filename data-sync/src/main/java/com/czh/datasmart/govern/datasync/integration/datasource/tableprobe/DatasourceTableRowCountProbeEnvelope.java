/**
 * @Author : Cui
 * @Date: 2026/07/09 22:44
 * @Description DataSmart Govern Backend - DatasourceTableRowCountProbeEnvelope.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.integration.datasource.tableprobe;

import lombok.Getter;
import lombok.Setter;

/**
 * datasource-management row-count probe 响应 envelope 的本地镜像。
 */
@Getter
@Setter
public class DatasourceTableRowCountProbeEnvelope {

    private Integer code;
    private String message;
    private DatasourceTableRowCountProbeResponse data;
}
