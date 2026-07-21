/**
 * @Author : Cui
 * @Date: 2026/07/22
 * @Description DataSmart Govern Backend - DataSourceStatusTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Protects the datasource list status contract shared by the console, task wizard and Agent clarification form.
 */
class DataSourceStatusTest {

    @Test
    void shouldAcceptConsoleAndPersistenceStatusNames() {
        assertThat(DataSourceStatus.normalizeQueryValue("enabled")).isEqualTo(DataSourceStatus.ACTIVE);
        assertThat(DataSourceStatus.normalizeQueryValue(" ACTIVE ")).isEqualTo(DataSourceStatus.ACTIVE);
        assertThat(DataSourceStatus.normalizeQueryValue("disabled")).isEqualTo(DataSourceStatus.INACTIVE);
        assertThat(DataSourceStatus.normalizeQueryValue("INACTIVE")).isEqualTo(DataSourceStatus.INACTIVE);
    }

    @Test
    void shouldRejectUnknownStatusInsteadOfSilentlyReturningAnEmptyList() {
        assertThatThrownBy(() -> DataSourceStatus.normalizeQueryValue("READY"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported datasource status");
    }
}
