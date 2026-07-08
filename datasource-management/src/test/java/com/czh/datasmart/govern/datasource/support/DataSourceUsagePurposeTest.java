/**
 * @Author : Cui
 * @Date: 2026/07/08 17:30
 * @Description DataSmart Govern Backend - DataSourceUsagePurposeTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * 数据源用途产品合同测试。
 *
 * <p>这组测试保护的是数据源创建与同步任务选择器之间的边界：新建数据源只能明确选择 SOURCE 或 TARGET，
 * 不能再通过空值或 BOTH 把同一条连接同时放进源端和目标端候选列表。</p>
 */
class DataSourceUsagePurposeTest {

    /**
     * 用户提交的合法用途应支持大小写和首尾空格归一化。
     */
    @Test
    void fromValueShouldResolveSourceAndTarget() {
        assertThat(DataSourceUsagePurpose.fromValue(" source ")).isEqualTo(DataSourceUsagePurpose.SOURCE);
        assertThat(DataSourceUsagePurpose.fromValue("TARGET")).isEqualTo(DataSourceUsagePurpose.TARGET);
    }

    /**
     * 空用途和历史 BOTH 都不能作为新建/更新请求的合法输入。
     */
    @Test
    void fromValueShouldRejectBlankAndBoth() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DataSourceUsagePurpose.fromValue(null))
                .withMessageContaining("用途不能为空");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DataSourceUsagePurpose.fromValue("BOTH"))
                .withMessageContaining("SOURCE、TARGET");
    }

    /**
     * 历史库兼容只发生在持久化数据读取路径，BOTH 会安全折叠为 SOURCE。
     */
    @Test
    void persistedLegacyBothShouldFallbackToSource() {
        assertThat(DataSourceUsagePurpose.fromPersistedValueOrDefault("BOTH"))
                .isEqualTo(DataSourceUsagePurpose.SOURCE);
        assertThat(DataSourceUsagePurpose.SOURCE.matchesRole("SOURCE")).isTrue();
        assertThat(DataSourceUsagePurpose.SOURCE.matchesRole("TARGET")).isFalse();
    }
}
