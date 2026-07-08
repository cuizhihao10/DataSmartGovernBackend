/**
 * @Author : Cui
 * @Date: 2026/07/08 16:42
 * @Description DataSmart Govern Backend - DatasourceReadOnlySqlClient.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.integration.datasource.sql;

import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;

/**
 * datasource-management 受控只读 SQL 能力客户端。
 *
 * <p>data-sync 只负责任务创建、调度和执行编排，不应该直接读取数据源密码或打开 JDBC 连接。
 * 因此 SQL 语句模式的“语法是否正确、表字段是否存在、SELECT 输出列是什么”都通过该接口委托给
 * datasource-management。这样两个微服务之间通过 JSON 契约解耦，未来从 HTTP 切到 gRPC、服务网格或
 * Kafka command 时，只需要替换实现类，不需要改创建向导业务逻辑。</p>
 */
public interface DatasourceReadOnlySqlClient {

    /**
     * 在指定数据源上执行一次受控只读 SQL 探测。
     *
     * @param datasourceId 源端数据源 ID
     * @param request 只读 SQL 探测请求镜像
     * @param actorContext 当前操作者上下文，用于透传租户、操作者和 traceId
     * @return datasource-management 返回的低敏执行结果镜像
     */
    DatasourceReadOnlySqlResponse execute(Long datasourceId,
                                          DatasourceReadOnlySqlRequest request,
                                          SyncActorContext actorContext);
}
