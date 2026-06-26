/**
 * @Author : Cui
 * @Date: 2026/06/26 23:39
 * @Description DataSmart Govern Backend - AgentArtifactObjectStoreMinioProperties.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent artifact MinIO 对象存储配置。
 *
 * <p>本配置只服务于 agent-runtime 内部对象存储探针 adapter。它不是给前端、模型或外部 Agent
 * 使用的下载配置，也不会被序列化到任何业务响应中。endpoint、bucket、accessKey、secretKey 都属于服务端
 * 基础设施配置，生产环境应通过环境变量、Nacos、K8s Secret 或企业密钥管理系统注入。</p>
 *
 * <p>为什么需要 `referencePrefixObjectKeyPrefixes`？因为 HTTP 层只允许传低敏 artifactReference，
 * 例如 `agent-artifact:run-command/receipt-001`。真实 objectName 必须由服务端根据受控映射生成，
 * 不能让调用方直接提交 bucket/key，否则 grant 引用会被误用成任意对象读取入口。</p>
 */
@ConfigurationProperties(prefix = "datasmart.agent-runtime.artifact-object-store.minio")
public class AgentArtifactObjectStoreMinioProperties {

    /**
     * 是否启用真实 MinIO/S3-compatible adapter。
     *
     * <p>默认 false，是为了保护本地学习环境和早期部署：没有配置 MinIO 凭据时，系统仍使用默认禁用 adapter，
     * 不会误把 artifact 读取标记为已经具备生产能力。生产环境开启前必须先确认 bucket、账号权限、DLP、
     * 下载审计和保留期策略。</p>
     */
    private boolean enabled = false;

    /**
     * MinIO 或 S3-compatible endpoint。
     *
     * <p>建议包含协议，例如 `http://localhost:9000` 或 `https://minio.internal:9000`。
     * 该值不能进入响应、日志或 runtime event，因为它属于内部基础设施定位信息。</p>
     */
    private String endpoint = "http://localhost:9000";

    /**
     * artifact 默认 bucket。
     *
     * <p>当前 adapter 采用“单 bucket + objectName 前缀分层”的模式，避免调用方在 artifactReference
     * 中携带 bucket 名。多 bucket 策略后续可以通过租户映射表扩展，但仍必须由服务端决定。</p>
     */
    private String bucket = "datasmart-agent-artifacts";

    /**
     * MinIO access key。
     *
     * <p>生产环境不要写入 yml 明文，必须通过环境变量或密钥系统注入。本字段只由 SDK builder 使用，
     * 不参与任何响应、异常消息或测试断言输出。</p>
     */
    private String accessKey = "";

    /**
     * MinIO secret key。
     *
     * <p>同 accessKey，生产环境必须走密钥注入；代码中任何异常都不能拼接该值。</p>
     */
    private String secretKey = "";

    /**
     * 可选 region。
     *
     * <p>MinIO 本地部署通常不需要 region，但某些 S3-compatible 网关或云厂商兼容层会要求 region。
     * 为空时不设置 region，避免对本地 MinIO 产生多余约束。</p>
     */
    private String region = "";

    /**
     * objectName 根前缀。
     *
     * <p>真实 objectName 会按 `{objectKeyRootPrefix}/{prefixMapping}/{logicalPath}` 生成。
     * 这样所有 Agent artifact 都集中在一个可治理命名空间下，便于后续做生命周期、归档、清理和审计。</p>
     */
    private String objectKeyRootPrefix = "agent-runtime/artifacts";

    /**
     * artifactReference 前缀到 objectName 子目录的映射。
     *
     * <p>只允许这些前缀被解析为 MinIO objectName。调用方提交 `https://`、真实路径、bucket/key 或未知协议时，
     * adapter 会 fail-closed。LinkedHashMap 保持声明顺序，方便后续加入更具体前缀时选择最长匹配。</p>
     */
    private Map<String, String> referencePrefixObjectKeyPrefixes = defaultReferencePrefixObjectKeyPrefixes();

    /**
     * 单次探针允许 adapter 读取的最大 sample 字节数。
     *
     * <p>Service 层已经有 64KB Host 硬上限，这里再给 adapter 一层配置上限，避免未来多个调用方直接复用
     * adapter 时绕过 Host 限制。较小 sample 已足够确认对象可读、计算指纹和做基础类型判断。</p>
     */
    private int maxProbeBytes = 64 * 1024;

    /**
     * 对象长度软上限。
     *
     * <p>超过该值不代表对象不可读，但 response 会携带低敏 issueCode，提醒运维后续应走分块读取、归档或专用下载流程。
     * 该配置可以防止任务日志、质量报告或导出文件无限膨胀后仍被 Agent 当作普通上下文处理。</p>
     */
    private long softMaxObjectBytes = 50L * 1024L * 1024L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getObjectKeyRootPrefix() {
        return objectKeyRootPrefix;
    }

    public void setObjectKeyRootPrefix(String objectKeyRootPrefix) {
        this.objectKeyRootPrefix = objectKeyRootPrefix;
    }

    public Map<String, String> getReferencePrefixObjectKeyPrefixes() {
        return referencePrefixObjectKeyPrefixes;
    }

    public void setReferencePrefixObjectKeyPrefixes(Map<String, String> referencePrefixObjectKeyPrefixes) {
        this.referencePrefixObjectKeyPrefixes = referencePrefixObjectKeyPrefixes == null
                ? defaultReferencePrefixObjectKeyPrefixes()
                : referencePrefixObjectKeyPrefixes;
    }

    public int getMaxProbeBytes() {
        return maxProbeBytes;
    }

    public void setMaxProbeBytes(int maxProbeBytes) {
        this.maxProbeBytes = maxProbeBytes;
    }

    public long getSoftMaxObjectBytes() {
        return softMaxObjectBytes;
    }

    public void setSoftMaxObjectBytes(long softMaxObjectBytes) {
        this.softMaxObjectBytes = softMaxObjectBytes;
    }

    private static Map<String, String> defaultReferencePrefixObjectKeyPrefixes() {
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put("agent-artifact:", "agent-artifact");
        defaults.put("artifact:", "artifact");
        defaults.put("artifact-ref:", "artifact-ref");
        defaults.put("command-output:", "command-output");
        defaults.put("task-artifact:", "task-artifact");
        defaults.put("minio-object:", "minio-object");
        return defaults;
    }
}
