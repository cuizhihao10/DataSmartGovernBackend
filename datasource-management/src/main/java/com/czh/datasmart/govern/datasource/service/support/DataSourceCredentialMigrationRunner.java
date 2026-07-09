/**
 * @Author : Cui
 * @Date: 2026/07/10 10:30
 * @Description DataSmart Govern Backend - DataSourceCredentialMigrationRunner.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czh.datasmart.govern.datasource.config.DataSourceCredentialProperties;
import com.czh.datasmart.govern.datasource.entity.DataSourceConfig;
import com.czh.datasmart.govern.datasource.mapper.DataSourceConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 历史明文数据源密码启动迁移器。
 *
 * <p>项目历史版本把 {@code datasource_config.password} 作为普通字符串保存。
 * 一次性要求用户手动重新录入所有数据源密码不现实，也会打断本地 E2E 和客户升级流程。
 * 因此这里采用“兼容读取 + 启动后台迁移”的策略：</p>
 *
 * <p>1. 应用运行时仍能识别历史明文并正常连接；</p>
 * <p>2. 服务启动后扫描没有 {@code ENC[v1]} 前缀的存量记录；</p>
 * <p>3. 只重写 password 和 update_time，不改租户、项目、用途、状态等业务字段；</p>
 * <p>4. 日志只输出迁移条数，不打印明文、密文、JDBC URL、用户名或连接失败详情。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSourceCredentialMigrationRunner implements ApplicationRunner {

    private final DataSourceCredentialProperties properties;
    private final DataSourceCredentialCipherSupport credentialCipherSupport;
    private final DataSourceConfigMapper dataSourceConfigMapper;

    /**
     * Spring Boot 启动完成后执行历史凭据迁移。
     */
    @Override
    public void run(ApplicationArguments args) {
        if (!credentialCipherSupport.isEncryptionEnabled()) {
            log.warn("数据源凭据新写入加密已关闭，本次跳过历史明文迁移；生产环境不建议关闭该能力");
            return;
        }
        credentialCipherSupport.validateRuntimeConfiguration();
        if (!Boolean.TRUE.equals(properties.getMigrateLegacyPlaintextOnStartup())) {
            log.info("数据源历史明文凭据启动迁移已关闭");
            return;
        }

        List<DataSourceConfig> configs = dataSourceConfigMapper.selectList(
                new LambdaQueryWrapper<DataSourceConfig>()
                        .select(DataSourceConfig::getId, DataSourceConfig::getPassword)
                        .isNotNull(DataSourceConfig::getPassword)
        );
        int scanned = 0;
        int migrated = 0;
        for (DataSourceConfig config : configs) {
            scanned++;
            if (config.getPassword() == null || config.getPassword().isBlank()
                    || credentialCipherSupport.isEncryptedValue(config.getPassword())) {
                continue;
            }
            DataSourceConfig patch = new DataSourceConfig();
            patch.setId(config.getId());
            patch.setPassword(credentialCipherSupport.encryptForStorage(config.getPassword()));
            patch.setUpdateTime(LocalDateTime.now());
            dataSourceConfigMapper.updateById(patch);
            migrated++;
        }

        if (migrated > 0) {
            log.info("数据源历史明文凭据迁移完成，scanned={}, migrated={}", scanned, migrated);
        } else {
            log.info("数据源历史明文凭据迁移检查完成，scanned={}, migrated=0", scanned);
        }
    }
}
