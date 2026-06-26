/**
 * @Author : Cui
 * @Date: 2026/06/26 23:45
 * @Description DataSmart Govern Backend - MinioAgentToolActionArtifactObjectStoreClient.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.config.AgentArtifactObjectStoreMinioProperties;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * MinIO/S3-compatible artifact 对象存储探针 adapter。
 *
 * <p>该类是真实对象存储接入的第一段落地实现，但它仍然不是下载服务。它只在
 * {@code datasmart.agent-runtime.artifact-object-store.minio.enabled=true} 时注册，默认情况下由
 * {@link DisabledAgentToolActionArtifactObjectStoreClient} 接管，避免本地环境缺少 MinIO 凭据时误执行对象读取。</p>
 *
 * <p>实现流程：</p>
 * <p>1. 用服务端 locator 把低敏 artifactReference 解析成内部 objectName；</p>
 * <p>2. 通过 `statObject` 读取对象长度、类型和版本相关信息；</p>
 * <p>3. 通过 `getObject` 的 length 限制只读取前缀 sample；</p>
 * <p>4. 返回内部 sample 给 Host probe service 计算指纹；HTTP 响应仍不会携带 sample 字节。</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "datasmart.agent-runtime.artifact-object-store.minio",
        name = "enabled",
        havingValue = "true"
)
public class MinioAgentToolActionArtifactObjectStoreClient implements AgentToolActionArtifactObjectStoreClient {

    private final AgentArtifactObjectStoreMinioProperties properties;
    private final AgentToolActionArtifactMinioObjectLocator objectLocator;

    /**
     * MinIOClient 是线程安全客户端，懒加载可以避免配置未启用或单测实例化时立刻校验 endpoint/credential。
     */
    private volatile MinioClient minioClient;

    public MinioAgentToolActionArtifactObjectStoreClient(AgentArtifactObjectStoreMinioProperties properties,
                                                         AgentToolActionArtifactMinioObjectLocator objectLocator) {
        this.properties = properties;
        this.objectLocator = objectLocator;
    }

    /**
     * 执行服务端对象探针。
     *
     * @param command 已经过 Host grant 复核的低敏读取命令。
     * @return 内部探针结果；sampleBytes 只允许被上层 Service 用于哈希和统计。
     */
    @Override
    public AgentToolActionArtifactObjectStoreProbeSample probe(AgentToolActionArtifactObjectStoreProbeCommand command) {
        try {
            validateConfiguration();
            String objectName = objectLocator.resolveObjectName(command.artifactReference());
            int probeBytes = resolveProbeBytes(command.maxProbeBytes());
            StatObjectResponse stat = client().statObject(
                    StatObjectArgs.builder()
                            .bucket(properties.getBucket())
                            .object(objectName)
                            .build()
            );
            byte[] sampleBytes = readSample(objectName, probeBytes);
            return successSample(stat, sampleBytes, probeBytes);
        } catch (ErrorResponseException exception) {
            return objectStoreError(exception);
        } catch (IllegalArgumentException exception) {
            return rejectedByLocalPolicy();
        } catch (Exception exception) {
            return genericFailure();
        }
    }

    /**
     * 懒加载 MinIO SDK 客户端。
     *
     * <p>这里不会把 endpoint、accessKey 或 secretKey 写入异常文本。MinIO SDK 抛出的异常也会在 probe 方法中被
     * 转换成低敏 issueCode，避免基础设施定位信息外泄。</p>
     */
    private MinioClient client() {
        MinioClient current = minioClient;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (minioClient == null) {
                MinioClient.Builder builder = MinioClient.builder()
                        .endpoint(requireText(properties.getEndpoint(), "endpoint"))
                        .credentials(
                                requireText(properties.getAccessKey(), "accessKey"),
                                requireText(properties.getSecretKey(), "secretKey")
                        );
                String region = safeText(properties.getRegion());
                if (region != null) {
                    builder.region(region);
                }
                minioClient = builder.build();
            }
            return minioClient;
        }
    }

    private byte[] readSample(String objectName, int probeBytes) throws Exception {
        try (GetObjectResponse response = client().getObject(
                GetObjectArgs.builder()
                        .bucket(properties.getBucket())
                        .object(objectName)
                        .offset(0L)
                        .length((long) probeBytes)
                        .build()
        )) {
            return response.readNBytes(probeBytes);
        }
    }

    private AgentToolActionArtifactObjectStoreProbeSample successSample(
            StatObjectResponse stat,
            byte[] sampleBytes,
            int probeBytes) {
        List<String> evidenceCodes = new ArrayList<>();
        evidenceCodes.add("MINIO_STAT_OBJECT_SUCCEEDED");
        evidenceCodes.add("MINIO_GET_OBJECT_SAMPLE_SUCCEEDED");
        evidenceCodes.add("MINIO_BUCKET_AND_OBJECT_KEY_NOT_RETURNED");
        if (stat.size() > probeBytes || sampleBytes.length >= probeBytes) {
            evidenceCodes.add("MINIO_OBJECT_SAMPLE_RANGE_LIMITED");
        }

        List<String> issueCodes = new ArrayList<>();
        if (properties.getSoftMaxObjectBytes() > 0 && stat.size() > properties.getSoftMaxObjectBytes()) {
            issueCodes.add("MINIO_OBJECT_EXCEEDS_SOFT_MAX_BYTES");
        }

        return new AgentToolActionArtifactObjectStoreProbeSample(
                true,
                true,
                normalizeContentType(stat.contentType()),
                stat.size(),
                sampleBytes,
                stat.size() > sampleBytes.length,
                objectVersionFingerprint(stat),
                evidenceCodes,
                issueCodes,
                List.of(
                        "MinIO 探针已确认对象可服务端读取；如需展示短预览，仍必须进入 body-read-final-checks。",
                        "完整下载仍需 durable grant store、DLP/恶意内容扫描、下载审计、限速和保留期策略。"
                )
        );
    }

    private AgentToolActionArtifactObjectStoreProbeSample objectStoreError(ErrorResponseException exception) {
        String code = exception.errorResponse() == null ? "" : exception.errorResponse().code();
        List<String> issueCodes = new ArrayList<>();
        issueCodes.add("MINIO_OBJECT_STORE_ERROR");
        if ("NoSuchKey".equalsIgnoreCase(code) || "NoSuchObject".equalsIgnoreCase(code)) {
            issueCodes.add("MINIO_OBJECT_NOT_FOUND");
        } else if ("AccessDenied".equalsIgnoreCase(code)) {
            issueCodes.add("MINIO_ACCESS_DENIED");
        }
        return failureSample(
                List.of("MINIO_STAT_OR_GET_OBJECT_FAILED"),
                issueCodes,
                List.of("检查 artifact 是否已物化、对象是否过期、服务账号是否具备最小只读权限。")
        );
    }

    private AgentToolActionArtifactObjectStoreProbeSample rejectedByLocalPolicy() {
        return failureSample(
                List.of("MINIO_OBJECT_LOCATOR_REJECTED_REFERENCE"),
                List.of("ARTIFACT_REFERENCE_NOT_MAPPABLE_TO_MINIO_OBJECT"),
                List.of("确认 artifactReference 使用受控前缀，且 logical path 不包含 URL、路径逃逸、bucket/key 或本机路径。")
        );
    }

    private AgentToolActionArtifactObjectStoreProbeSample genericFailure() {
        return failureSample(
                List.of("MINIO_OBJECT_PROBE_FAILED"),
                List.of("MINIO_OBJECT_PROBE_UNAVAILABLE"),
                List.of("检查 MinIO endpoint、bucket、凭据、网络、TLS 和对象生命周期配置；不要在错误响应中暴露内部地址。")
        );
    }

    private AgentToolActionArtifactObjectStoreProbeSample failureSample(
            List<String> evidenceCodes,
            List<String> issueCodes,
            List<String> recommendedActions) {
        return new AgentToolActionArtifactObjectStoreProbeSample(
                true,
                false,
                null,
                null,
                new byte[0],
                false,
                null,
                evidenceCodes,
                issueCodes,
                recommendedActions
        );
    }

    private void validateConfiguration() {
        requireText(properties.getEndpoint(), "endpoint");
        requireText(properties.getBucket(), "bucket");
        requireText(properties.getAccessKey(), "accessKey");
        requireText(properties.getSecretKey(), "secretKey");
        if (properties.getMaxProbeBytes() <= 0) {
            throw new IllegalArgumentException("maxProbeBytes 必须大于 0");
        }
    }

    private int resolveProbeBytes(int commandProbeBytes) {
        int configuredLimit = properties.getMaxProbeBytes() <= 0 ? 64 * 1024 : properties.getMaxProbeBytes();
        return Math.max(1, Math.min(commandProbeBytes, configuredLimit));
    }

    private String objectVersionFingerprint(StatObjectResponse stat) {
        String source = String.join("|",
                safeText(stat.etag()) == null ? "" : stat.etag(),
                safeText(stat.versionId()) == null ? "" : stat.versionId(),
                String.valueOf(stat.size()),
                normalizeContentType(stat.contentType()) == null ? "" : normalizeContentType(stat.contentType())
        );
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(source.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "object-version-sha256:" + HexFormat.of().formatHex(hashed).substring(0, 24);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256，无法生成对象版本指纹", exception);
        }
    }

    private String normalizeContentType(String value) {
        String text = safeText(value);
        return text == null ? "application/octet-stream" : text.toLowerCase(Locale.ROOT);
    }

    private String requireText(String value, String fieldName) {
        String text = safeText(value);
        if (text == null) {
            throw new IllegalArgumentException("MinIO artifact object-store 配置缺少 " + fieldName);
        }
        return text;
    }

    private String safeText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
