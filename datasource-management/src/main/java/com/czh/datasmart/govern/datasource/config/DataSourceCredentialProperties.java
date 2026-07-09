/**
 * @Author : Cui
 * @Date: 2026/07/10 10:30
 * @Description DataSmart Govern Backend - DataSourceCredentialProperties.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 数据源连接凭据安全配置。
 *
 * <p>数据源密码和用户登录密码的安全模型完全不同：用户登录密码只需要“校验是否匹配”，
 * 因此可以使用 Argon2、bcrypt、PBKDF2 等不可逆哈希；而外部数据源连接密码必须在运行时还原给 JDBC
 * DriverManager 或连接池使用，因此只能走“可逆加密 + 主密钥外置 + 接口脱敏 + 审计不落密钥”的路线。</p>
 *
 * <p>本配置类先提供应用层字段级加密能力，作为当前阶段最小可落地的生产化安全闭环。
 * 后续如果接入 Vault、KMS、云 Secret Manager 或企业密钥中心，只需要替换
 * {@code DataSourceCredentialCipherSupport} 背后的实现，数据源管理、元数据发现、同步执行等业务链路不需要重写。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "datasmart.datasource.credential")
public class DataSourceCredentialProperties {

    /**
     * 是否启用新写入凭据的加密存储。
     *
     * <p>默认开启。关闭时新写入会保持历史明文兼容模式，但系统仍然会尝试解密已经带 {@code ENC[v1]} 前缀的历史密文，
     * 避免因为临时切换开关导致已有数据源无法连接。生产环境不建议关闭。</p>
     */
    private Boolean encryptionEnabled = true;

    /**
     * 数据源凭据主密钥。
     *
     * <p>推荐通过环境变量 {@code DATASMART_DATASOURCE_CREDENTIAL_MASTER_KEY} 注入。
     * 最佳格式是 32 字节随机密钥的 Base64 文本；为了本地开发和私有化部署易用性，也允许传入高强度口令，
     * 系统会用 SHA-256 派生 AES-256 key。注意：主密钥绝不能提交到 Git、配置中心明文或前端产物中。</p>
     */
    private String masterKey;

    /**
     * 密钥版本标识。
     *
     * <p>密文会保存该字段，例如 {@code ENC[v1]:local-dev-v1:iv:ciphertext}。
     * 这样未来做密钥轮换时，可以先保留旧 key 解密能力，再用新 key 重加密，不必猜测某条密文由哪个版本产生。</p>
     */
    private String keyId = "local-dev-v1";

    /**
     * 启动时是否迁移历史明文密码。
     *
     * <p>开启后，服务启动会扫描 {@code datasource_config.password} 中没有 {@code ENC[v1]} 前缀的记录，
     * 并原地重写为密文。迁移过程不会打印原始密码、密文正文、JDBC URL 或用户名，只输出低敏统计。</p>
     */
    private Boolean migrateLegacyPlaintextOnStartup = true;

    /**
     * 本地开发是否允许使用兜底密钥。
     *
     * <p>该能力只用于开发、测试、演示环境，避免每个开发者第一次启动都必须先准备随机主密钥。
     * 如果当前激活 profile 包含 prod/production 且未提供 masterKey，系统仍会拒绝启动。</p>
     */
    private Boolean allowLocalFallbackKey = true;

    /**
     * 生产 profile 下缺少 masterKey 时是否失败启动。
     *
     * <p>商业化交付时必须保持为 true，否则容器重建、配置漂移或多副本部署都可能因为不同实例使用不同兜底 key，
     * 导致历史密文无法解密，甚至把安全问题隐藏成普通连接失败。</p>
     */
    private Boolean failOnMissingMasterKeyInProduction = true;

    /**
     * 本地开发兜底密钥材料。
     *
     * <p>该值不是生产密钥，只用于没有配置 {@code masterKey} 的 dev/test 场景。
     * 生产环境必须通过 Secret Manager、Kubernetes Secret、Docker secret 或安全环境变量注入真实随机密钥。</p>
     */
    private String localFallbackKey = "datasmart-local-dev-datasource-credential-key-change-before-production";
}
