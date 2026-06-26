/**
 * @Author : Cui
 * @Date: 2026/06/26 23:14
 * @Description DataSmart Govern Backend - DisabledAgentToolActionArtifactObjectStoreClient.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 默认禁用的对象存储客户端实现。
 *
 * <p>在没有接入真实 MinIO/S3-compatible adapter 之前，系统不能假装对象存储读取已经可用。
 * 因此默认实现采用 fail-safe 行为：返回“未执行探针、对象不可用、需要启用真实 adapter”的低敏结果。
 * 这样本地学习、单测和早期部署不会因为缺少 MinIO 配置而启动失败，但业务调用也不会误以为已经完成真实读取。</p>
 *
 * <p>`@ConditionalOnMissingBean` 的意义是：未来新增 `MinioAgentToolActionArtifactObjectStoreClient`
 * 后，Spring 会自动让真实实现替换默认禁用实现，而不需要修改 Service 或 Controller。</p>
 */
@Component
@ConditionalOnMissingBean(AgentToolActionArtifactObjectStoreClient.class)
public class DisabledAgentToolActionArtifactObjectStoreClient implements AgentToolActionArtifactObjectStoreClient {

    /**
     * 返回禁用态探针结果。
     *
     * @param command 已通过上游校验的探针命令；默认实现不会访问对象存储。
     * @return fail-safe 的不可用结果，不包含任何正文、URL、bucket/key 或内部配置。
     */
    @Override
    public AgentToolActionArtifactObjectStoreProbeSample probe(AgentToolActionArtifactObjectStoreProbeCommand command) {
        return new AgentToolActionArtifactObjectStoreProbeSample(
                false,
                false,
                null,
                null,
                new byte[0],
                false,
                null,
                List.of("OBJECT_STORE_CLIENT_DEFAULT_DISABLED"),
                List.of("OBJECT_STORE_CLIENT_DISABLED"),
                List.of(
                        "为 agent-runtime 配置真实 MinIO/S3-compatible adapter 后再执行对象存储探针。",
                        "在启用真实 adapter 前，artifact 读取链路只能完成授权和 final-check 合同，不能确认对象可读取。"
                )
        );
    }
}
