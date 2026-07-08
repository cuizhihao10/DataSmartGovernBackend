/**
 * @Author : Cui
 * @Date: 2026/07/08 16:43
 * @Description DataSmart Govern Backend - DatasourceReadOnlySqlEnvelope.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.integration.datasource.sql;

import lombok.Getter;
import lombok.Setter;

/**
 * datasource-management 统一响应 envelope 镜像。
 *
 * <p>平台 Java 服务统一使用 {@code code/message/data} 响应结构。data-sync 在客户端层解开 envelope，
 * 业务支持类只处理领域结果，避免每个业务流程都重复判断 code 和 data。</p>
 */
@Getter
@Setter
public class DatasourceReadOnlySqlEnvelope {

    private Integer code;
    private String message;
    private DatasourceReadOnlySqlResponse data;
}
