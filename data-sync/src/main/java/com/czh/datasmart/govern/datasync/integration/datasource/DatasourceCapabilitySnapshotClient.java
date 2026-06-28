/**
 * @Author : Cui
 * @Date: 2026/06/29 00:09
 * @Description DataSmart Govern Backend - DatasourceCapabilitySnapshotClient.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.integration.datasource;

import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;

/**
 * datasource-management 低敏能力快照客户端接口。
 *
 * <p>接口只暴露 data-sync 需要的读取动作，而不暴露 HTTP、RestClient、URL、Header 或响应 envelope 细节。
 * 这样 service/support 层可以专注业务判断：是否需要补全 connector type、是否允许模板规划、租户/项目是否一致；
 * 真正的跨服务通信细节集中在 implementation 中，后续从 HTTP 切到服务发现、gRPC、Feign 或缓存代理时不会影响模板逻辑。</p>
 */
@FunctionalInterface
public interface DatasourceCapabilitySnapshotClient {

    /**
     * 按 datasourceId 读取低敏能力快照。
     *
     * @param datasourceId 平台内数据源主键，只用于定位 datasource-management 中的低敏能力事实。
     * @param actorContext 当前 data-sync 调用上下文，用于透传 traceId 和后续服务间审计，不用于拼接敏感连接信息。
     * @return datasource-management 返回的低敏能力快照；不得包含连接串、账号、密码、SQL、样本数据或内部 endpoint。
     */
    DatasourceCapabilitySnapshotView getSnapshot(Long datasourceId, SyncActorContext actorContext);
}
