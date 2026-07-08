/**
 * @Author : Cui
 * @Date: 2026/07/09 22:41
 * @Description DataSmart Govern Backend - DatasourceTableRowCountProbeClient.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.integration.datasource.tableprobe;

import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;

/**
 * datasource-management 表行数探测客户端接口。
 *
 * <p>data-sync 在执行预检查时需要知道目标表是否为空，但不能直接连接目标库。
 * 该接口就是跨服务边界：data-sync 只请求低敏 row-count 事实，datasource-management 继续负责凭据、JDBC、
 * 只读连接、安全 SQL 生成和 internal 服务账号校验。</p>
 */
public interface DatasourceTableRowCountProbeClient {

    /**
     * 探测目标表行数。
     *
     * @param request row-count 探测请求镜像。
     * @param actorContext 当前操作者上下文，用于 trace 和内部 Header 透传。
     * @return 低敏行数探测结果。
     */
    DatasourceTableRowCountProbeResponse probeRowCount(DatasourceTableRowCountProbeRequest request,
                                                       SyncActorContext actorContext);
}
