/**
 * @Author : Cui
 * @Date: 2026/07/10 10:30
 * @Description DataSmart Govern Backend - DataSourceCredentialCipherSupportTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.support;

import com.czh.datasmart.govern.datasource.config.DataSourceCredentialProperties;
import com.czh.datasmart.govern.datasource.entity.DataSourceConfig;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 数据源凭据加密组件测试。
 *
 * <p>这组测试不连接真实数据库，而是专门保护“密码如何存、如何取、如何对外隐藏”这条安全合同。
 * 一旦有人未来为了方便调试把加密关闭、把密文返回给前端、或者把生产环境缺 key 降级成本地默认值，
 * 这些用例应该第一时间失败。</p>
 */
class DataSourceCredentialCipherSupportTest {

    @Test
    void encryptForStorageShouldReturnVersionedCiphertextAndDecryptBack() {
        DataSourceCredentialCipherSupport support = newSupportWithMasterKey();

        String encrypted = support.encryptForStorage("DataSmart@123");

        assertThat(encrypted).startsWith(DataSourceCredentialCipherSupport.ENCRYPTED_VALUE_PREFIX + ":test-key-v1:");
        assertThat(encrypted).doesNotContain("DataSmart@123");
        assertThat(support.decryptForUse(encrypted)).isEqualTo("DataSmart@123");
    }

    @Test
    void decryptForUseShouldKeepLegacyPlaintextCompatible() {
        DataSourceCredentialCipherSupport support = newSupportWithMasterKey();

        assertThat(support.decryptForUse("legacy-plain-password")).isEqualTo("legacy-plain-password");
    }

    @Test
    void sanitizeForApiShouldNeverExposePasswordStorageValue() {
        DataSourceCredentialCipherSupport support = newSupportWithMasterKey();
        DataSourceConfig source = new DataSourceConfig();
        source.setId(100L);
        source.setName("生产订单库");
        source.setPassword(support.encryptForStorage("secret-password"));

        DataSourceConfig sanitized = support.sanitizeForApi(source);

        assertThat(sanitized).isNotSameAs(source);
        assertThat(sanitized.getId()).isEqualTo(100L);
        assertThat(sanitized.getName()).isEqualTo("生产订单库");
        assertThat(sanitized.getPassword()).isNull();
        assertThat(source.getPassword()).startsWith(DataSourceCredentialCipherSupport.ENCRYPTED_VALUE_PREFIX);
    }

    @Test
    void productionProfileShouldFailFastWhenMasterKeyMissing() {
        DataSourceCredentialProperties properties = new DataSourceCredentialProperties();
        properties.setMasterKey("");
        properties.setAllowLocalFallbackKey(true);
        properties.setFailOnMissingMasterKeyInProduction(true);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        DataSourceCredentialCipherSupport support = new DataSourceCredentialCipherSupport(properties, environment);

        assertThatThrownBy(support::validateRuntimeConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("生产环境必须配置 DATASMART_DATASOURCE_CREDENTIAL_MASTER_KEY");
    }

    private DataSourceCredentialCipherSupport newSupportWithMasterKey() {
        DataSourceCredentialProperties properties = new DataSourceCredentialProperties();
        properties.setKeyId("test-key-v1");
        properties.setMasterKey(Base64.getEncoder().encodeToString(
                "12345678901234567890123456789012".getBytes(StandardCharsets.UTF_8)));
        return new DataSourceCredentialCipherSupport(properties, new MockEnvironment());
    }
}
