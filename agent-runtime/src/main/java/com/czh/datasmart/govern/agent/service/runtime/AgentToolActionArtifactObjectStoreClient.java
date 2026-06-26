/**
 * @Author : Cui
 * @Date: 2026/06/26 23:13
 * @Description DataSmart Govern Backend - AgentToolActionArtifactObjectStoreClient.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

/**
 * artifact 对象存储客户端抽象。
 *
 * <p>这个接口是刻意增加的“替换缝”：当前项目固定使用 MinIO 作为对象存储底座，但业务服务不应该直接把 MinIO SDK
 * 调用写进 grant、final-check 或 worker receipt 服务里。真实商业部署里，客户可能要求切换到 S3-compatible
 * 对象存储、私有云 OSS、冷归档或带 DLP 网关的 artifact service。只要这些实现遵守本接口的低敏输入/输出约束，
 * 上层 Host 控制面就不需要重构。</p>
 *
 * <p>注意：接口方法允许实现类在进程内短暂持有 sample 字节，但这些字节只能返回给
 * {@link AgentToolActionArtifactObjectStoreProbeService} 做哈希、长度和截断判断，不能直接序列化到 HTTP 响应、日志、
 * runtime event 或投影中。</p>
 */
public interface AgentToolActionArtifactObjectStoreClient {

    /**
     * 在服务端对 artifact 对象做只读探针。
     *
     * @param command 已经过 Host grant 复核后的低敏读取命令，不包含 bucket/key、URL 或真实路径。
     * @return adapter 探针结果；结果中的 sampleBytes 仍是内部短生命周期数据，调用方必须继续做低敏响应裁剪。
     */
    AgentToolActionArtifactObjectStoreProbeSample probe(AgentToolActionArtifactObjectStoreProbeCommand command);
}
