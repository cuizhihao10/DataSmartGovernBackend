/**
 * @Author : Cui
 * @Date: 2026/07/07 23:59
 * @Description DataSmart Govern Backend - DatasourceMetadataDiscoveryClient.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.integration.datasource.metadata;

import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;

/**
 * datasource-management 元数据发现客户端。
 *
 * <p>data-sync 需要在 SCHEMA_FULL / DATABASE_FULL 场景中知道源端有哪些表，但它不能绕过
 * datasource-management 自己连接源库。这个接口就是模块边界：data-sync 只拿到低敏表/字段摘要，
 * datasource-management 继续负责数据源凭据、连接池、元数据缓存和权限保护。</p>
 */
public interface DatasourceMetadataDiscoveryClient {

    /**
     * 发现数据源元数据。
     *
     * @param datasourceId datasource-management 中登记的源数据源 ID。
     * @param request 元数据发现请求。
     * @param actorContext 当前操作者或服务账号上下文。
     * @return 低敏元数据摘要。
     */
    DatasourceMetadataDiscoveryResponse discover(Long datasourceId,
                                                 DatasourceMetadataDiscoveryRequest request,
                                                 SyncActorContext actorContext);
}
