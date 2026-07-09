/**
 * @Author : Cui
 * @Date: 2026/07/10 10:30
 * @Description DataSmart Govern Backend - DataSourceCredentialCipherSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.support;

import com.czh.datasmart.govern.datasource.config.DataSourceCredentialProperties;
import com.czh.datasmart.govern.datasource.entity.DataSourceConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 数据源连接凭据加密、解密与接口脱敏支撑组件。
 *
 * <p>这个类是 datasource-management 的“凭据安全边界”。业务代码不应该自己判断密码是不是密文，
 * 也不应该直接把 {@code datasource_config.password} 返回给接口调用方。统一入口有几个好处：</p>
 *
 * <p>1. 新建和编辑数据源时，所有新密码都会以 AES-GCM 密文落库；</p>
 * <p>2. 连接测试、元数据发现、只读 SQL、同步执行等内部链路可以透明解密，不需要知道存储格式；</p>
 * <p>3. 历史明文记录可以先兼容读取，再由启动迁移器逐步重写为密文；</p>
 * <p>4. API 返回统一清空 password 字段，避免前端、网关、浏览器缓存或日志采集器看到明文或密文；</p>
 * <p>5. 后续切换到 Vault/KMS 时，只需要替换本类，不会把密钥系统侵入每个业务服务。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSourceCredentialCipherSupport {

    /**
     * 当前密文版本前缀。
     *
     * <p>带前缀的值表示已经由平台加密；没有前缀的值会被视为历史明文，仅用于兼容读取和启动迁移。</p>
     */
    public static final String ENCRYPTED_VALUE_PREFIX = "ENC[v1]";

    /**
     * AES-GCM 推荐使用 96 bit IV，也就是 12 字节。
     */
    private static final int GCM_IV_LENGTH_BYTES = 12;

    /**
     * GCM 认证标签长度，128 bit 是 Java 和主流 KMS/Vault SDK 的常见默认值。
     */
    private static final int GCM_TAG_LENGTH_BITS = 128;

    /**
     * AES-GCM 标准变换名称。
     */
    private static final String AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding";

    /**
     * 关联认证数据。
     *
     * <p>AAD 不参与密文输出，但会参与 GCM 认证标签计算。这样即使有人把本密文复制到其他业务字段，
     * 只要 AAD 不一致也无法正常解密，有助于约束“密文只能用于 datasource_config.password 语义”。</p>
     */
    private static final String AAD_PREFIX = "datasmart:datasource-management:datasource_config.password:v1:";

    private final DataSourceCredentialProperties properties;
    private final Environment environment;
    private final SecureRandom secureRandom = new SecureRandom();
    private final AtomicBoolean fallbackKeyWarningLogged = new AtomicBoolean(false);

    /**
     * 缓存后的 AES key。
     *
     * <p>主密钥来自环境或 Secret Manager，不应在每次连接时重复解析。这里缓存的是 JVM 内存中的密钥对象，
     * 不会写入日志、响应或数据库。后续如要支持在线密钥轮换，可以把该缓存替换为按 keyId 获取的 key ring。</p>
     */
    private volatile SecretKeySpec cachedSecretKey;

    /**
     * 判断当前是否会对新写入凭据执行加密。
     */
    public boolean isEncryptionEnabled() {
        return Boolean.TRUE.equals(properties.getEncryptionEnabled());
    }

    /**
     * 判断一个存储值是否已经是 DataSmart 当前版本密文。
     */
    public boolean isEncryptedValue(String value) {
        return value != null && value.startsWith(ENCRYPTED_VALUE_PREFIX + ":");
    }

    /**
     * 启动期校验凭据加密运行时配置。
     *
     * <p>这个方法看似只是提前解析 key，实际是一个生产安全闸门：如果 prod/production profile 缺少 masterKey，
     * 系统必须在启动阶段失败，而不是等到用户创建数据源、执行同步任务或元数据发现时才把安全配置问题暴露成普通连接异常。</p>
     */
    public void validateRuntimeConfiguration() {
        if (isEncryptionEnabled()) {
            resolveSecretKey();
        }
    }

    /**
     * 将用户输入的连接密码转换为可落库形式。
     *
     * <p>调用方传入的是“业务意义上的明文密码”。当加密开关开启时，本方法返回 AES-GCM 密文；
     * 当加密开关关闭时，返回原值以兼容历史开发环境。为了支持历史迁移和幂等修复，如果值已经带密文前缀，
     * 本方法会直接返回，不会重复加密。</p>
     */
    public String encryptForStorage(String plaintextPassword) {
        if (plaintextPassword == null) {
            throw new IllegalArgumentException("数据源连接密码不能为空");
        }
        if (!isEncryptionEnabled() || isEncryptedValue(plaintextPassword)) {
            return plaintextPassword;
        }
        try {
            String keyId = normalizedKeyId();
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, resolveSecretKey(), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            cipher.updateAAD(buildAad(keyId));
            byte[] encryptedPayload = cipher.doFinal(plaintextPassword.getBytes(StandardCharsets.UTF_8));

            return ENCRYPTED_VALUE_PREFIX
                    + ":" + keyId
                    + ":" + base64UrlEncode(iv)
                    + ":" + base64UrlEncode(encryptedPayload);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("数据源连接密码加密失败，请检查 JDK 加密能力和主密钥配置", exception);
        }
    }

    /**
     * 将数据库中的存储值还原为 JDBC 可使用的真实连接密码。
     *
     * <p>如果值带 {@code ENC[v1]} 前缀，则执行 AES-GCM 解密；如果不带前缀，则视为历史明文并原样返回。
     * 这条兼容逻辑非常重要：系统不能因为刚升级到加密版本，就让历史数据源全部失效；同时启动迁移器会把这些明文逐步重写。</p>
     */
    public String decryptForUse(String storedPassword) {
        if (storedPassword == null) {
            throw new IllegalArgumentException("数据源连接密码缺失，不能打开 JDBC 连接");
        }
        if (!isEncryptedValue(storedPassword)) {
            return storedPassword;
        }
        String[] parts = storedPassword.split(":", 4);
        if (parts.length != 4 || !ENCRYPTED_VALUE_PREFIX.equals(parts[0])) {
            throw new IllegalStateException("数据源连接密码密文格式不正确，请检查迁移数据或密钥版本");
        }
        String keyId = parts[1];
        try {
            byte[] iv = base64UrlDecode(parts[2]);
            byte[] encryptedPayload = base64UrlDecode(parts[3]);
            Cipher cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, resolveSecretKey(), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            cipher.updateAAD(buildAad(keyId));
            byte[] plaintext = cipher.doFinal(encryptedPayload);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("数据源连接密码解密失败，请检查 masterKey 是否与加密时一致", exception);
        }
    }

    /**
     * 构造一个面向 API 返回的低敏副本。
     *
     * <p>这里不是简单地“把密码打码成 ******”。打码容易让调用方误以为可以把该值回传保存，
     * 进而把真实密码覆盖成星号。正确做法是返回 {@code null}，明确表达“后端保存了凭据，但不会把它交给前端”。</p>
     */
    public DataSourceConfig sanitizeForApi(DataSourceConfig source) {
        if (source == null) {
            return null;
        }
        DataSourceConfig sanitized = new DataSourceConfig();
        BeanUtils.copyProperties(source, sanitized);
        sanitized.setPassword(null);
        return sanitized;
    }

    /**
     * 批量构造 API 低敏副本，主要用于分页列表。
     */
    public List<DataSourceConfig> sanitizeForApi(List<DataSourceConfig> sources) {
        if (sources == null || sources.isEmpty()) {
            return List.of();
        }
        return sources.stream().map(this::sanitizeForApi).toList();
    }

    private SecretKeySpec resolveSecretKey() {
        SecretKeySpec local = cachedSecretKey;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (cachedSecretKey == null) {
                cachedSecretKey = new SecretKeySpec(resolveKeyMaterial(), "AES");
            }
            return cachedSecretKey;
        }
    }

    private byte[] resolveKeyMaterial() {
        if (hasText(properties.getMasterKey())) {
            return normalizeConfiguredMasterKey(properties.getMasterKey().trim());
        }
        if (Boolean.TRUE.equals(properties.getFailOnMissingMasterKeyInProduction()) && isProductionProfile()) {
            throw new IllegalStateException("生产环境必须配置 DATASMART_DATASOURCE_CREDENTIAL_MASTER_KEY，不能使用本地兜底密钥");
        }
        if (!Boolean.TRUE.equals(properties.getAllowLocalFallbackKey())) {
            throw new IllegalStateException("未配置数据源凭据 masterKey，且已关闭本地兜底密钥");
        }
        if (fallbackKeyWarningLogged.compareAndSet(false, true)) {
            log.warn("当前 datasource-management 未配置数据源凭据 masterKey，正在使用 dev/test 兜底密钥；生产环境必须改为 Secret Manager 或安全环境变量注入");
        }
        return sha256(properties.getLocalFallbackKey());
    }

    private byte[] normalizeConfiguredMasterKey(String configuredMasterKey) {
        byte[] base64Decoded = tryBase64Decode(configuredMasterKey);
        if (isSupportedAesKeyLength(base64Decoded)) {
            return base64Decoded;
        }
        byte[] hexDecoded = tryHexDecode(configuredMasterKey);
        if (isSupportedAesKeyLength(hexDecoded)) {
            return hexDecoded;
        }
        /*
         * 如果用户提供的是高强度口令而不是原始随机 key，则使用 SHA-256 派生 AES-256 key。
         * 这不是为了鼓励短口令，而是为了兼容私有化部署中“通过 Secret Manager 管理长随机字符串”的常见形态。
         */
        return sha256(configuredMasterKey);
    }

    private boolean isProductionProfile() {
        return Arrays.stream(environment.getActiveProfiles())
                .map(profile -> profile.toLowerCase(Locale.ROOT))
                .anyMatch(profile -> profile.equals("prod") || profile.equals("production"));
    }

    private String normalizedKeyId() {
        if (!hasText(properties.getKeyId())) {
            return "local-dev-v1";
        }
        return properties.getKeyId().trim().replace(':', '_');
    }

    private byte[] buildAad(String keyId) {
        return (AAD_PREFIX + keyId).getBytes(StandardCharsets.UTF_8);
    }

    private String base64UrlEncode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private byte[] base64UrlDecode(String value) {
        return Base64.getUrlDecoder().decode(value);
    }

    private byte[] tryBase64Decode(String value) {
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException exception) {
            try {
                return Base64.getUrlDecoder().decode(value);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
    }

    private byte[] tryHexDecode(String value) {
        if (value.length() % 2 != 0 || !value.matches("(?i)[0-9a-f]+")) {
            return null;
        }
        byte[] bytes = new byte[value.length() / 2];
        for (int index = 0; index < value.length(); index += 2) {
            bytes[index / 2] = (byte) Integer.parseInt(value.substring(index, index + 2), 16);
        }
        return bytes;
    }

    private boolean isSupportedAesKeyLength(byte[] bytes) {
        return bytes != null && (bytes.length == 16 || bytes.length == 24 || bytes.length == 32);
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256，无法派生数据源凭据加密 key", exception);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
