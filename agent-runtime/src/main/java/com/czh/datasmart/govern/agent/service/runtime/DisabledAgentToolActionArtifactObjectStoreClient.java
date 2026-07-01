/**
 * @Author : Cui
 * @Date: 2026/06/26 23:14
 * @Description DataSmart Govern Backend - DisabledAgentToolActionArtifactObjectStoreClient.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 默认禁用的对象存储客户端实现。
 *
 * <p>在没有接入真实 MinIO/S3-compatible adapter 之前，系统不能假装对象存储读取已经可用。
 * 因此默认实现采用 fail-safe 行为：返回“未执行探针、对象不可用、需要启用真实 adapter”的低敏结果。
 * 这样本地学习、单测和早期部署不会因为缺少 MinIO 配置而启动失败，但业务调用也不会误以为已经完成真实读取。</p>
 *
 * <p>这里使用“MinIO 未显式启用时注册”的条件，而不是只依赖 {@code @ConditionalOnMissingBean}。
 * 普通组件扫描场景下，缺失 Bean 条件容易受扫描与条件评估顺序影响；对本地 E2E 来说，
 * 更确定的规则是：未设置 {@code datasmart.agent-runtime.artifact-object-store.minio.enabled=true}
 * 时，一定注册 fail-safe disabled adapter；显式启用 MinIO 时，再由真实 adapter 接管。</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "datasmart.agent-runtime.artifact-object-store.minio",
        name = "enabled",
        havingValue = "false",
        matchIfMissing = true
)
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
