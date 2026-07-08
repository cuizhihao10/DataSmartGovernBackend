/**
 * @Author : Cui
 * @Date: 2026/07/09 00:00
 * @Description DataSmart Govern Backend - DataSourceManagementServiceImplTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.impl;

import com.czh.datasmart.govern.datasource.entity.DataSourceConnectionTestResult;
import com.czh.datasmart.govern.datasource.service.support.ConnectorCapabilityRegistry;
import com.czh.datasmart.govern.datasource.service.support.DataSourceMetadataDiscoverySupport;
import com.czh.datasmart.govern.datasource.service.support.DataSourceReadOnlySqlSupport;
import com.czh.datasmart.govern.datasource.support.ConnectionTestStatus;
import com.czh.datasmart.govern.datasource.support.DataSourceType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 数据源管理服务的连接类型一致性测试。
 *
 * <p>这组用例不连接真实 MySQL/PostgreSQL，而是保护“连接器类型必须先和 JDBC URL 前缀一致”这条准入规则。
 * 真实建连前就能拦住明显错误，才能避免 DriverManager 根据 URL 自动选择 MySQL 驱动，
 * 最终把一条 MySQL 连接错误地登记成 PostgreSQL 数据源。</p>
 */
class DataSourceManagementServiceImplTest {

    @Test
    void temporaryConnectionTestShouldFailWhenTypeAndJdbcUrlDoNotMatch() {
        DataSourceManagementServiceImpl service = newService();

        DataSourceConnectionTestResult result = service.testConnection(
                "PostgreSQL",
                "jdbc:mysql://localhost:3306/datasmart_govern",
                "datasmart",
                "secret"
        );

        assertThat(result.getTestStatus()).isEqualTo(ConnectionTestStatus.FAILED);
        assertThat(result.getMessage())
                .contains("数据源类型与 JDBC URL 不一致")
                .contains("PostgreSQL")
                .contains("jdbc:mysql:");
        assertThat(result.getProductName()).isNull();
    }

    @Test
    void dataSourceTypeShouldAcceptDisplayNameAndCheckJdbcUrlPrefix() {
        assertThat(DataSourceType.fromValue("SQL Server")).isEqualTo(DataSourceType.SQLSERVER);
        assertThat(DataSourceType.POSTGRESQL.matchesJdbcUrl("jdbc:postgresql://localhost:5432/app")).isTrue();
        assertThat(DataSourceType.POSTGRESQL.matchesJdbcUrl("jdbc:mysql://localhost:3306/app")).isFalse();
    }

    private DataSourceManagementServiceImpl newService() {
        return new DataSourceManagementServiceImpl(
                mock(DataSourceReadOnlySqlSupport.class),
                mock(DataSourceMetadataDiscoverySupport.class),
                mock(ConnectorCapabilityRegistry.class)
        );
    }
}
