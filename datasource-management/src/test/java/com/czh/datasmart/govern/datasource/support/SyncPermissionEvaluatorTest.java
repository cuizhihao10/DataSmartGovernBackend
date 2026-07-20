/**
 * @Author : Cui
 * @Date: 2026/07/21 01:05
 * @Description DataSmart Govern Backend - SyncPermissionEvaluatorTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数据源本地能力矩阵回归测试。
 *
 * <p>实例 ACL 由 Controller/Service 在进入该矩阵前校验；这里固定的是授权用户进入结构发现和
 * 受控只读查询能力层后的角色资格，同时确保普通用户仍不能预览样本数据。</p>
 */
class SyncPermissionEvaluatorTest {

    private final SyncPermissionEvaluator evaluator = new SyncPermissionEvaluator();

    @Test
    void ordinaryUserShouldUseAuthorizedDatasourceForTaskConfiguration() {
        assertThat(evaluator.canAccess(
                "ORDINARY_USER",
                SyncPermissionResource.DATASOURCE_METADATA,
                SyncPermissionAction.VIEW_STRUCTURE)).isTrue();
        assertThat(evaluator.canAccess(
                "ORDINARY_USER",
                SyncPermissionResource.DATASOURCE_READONLY_QUERY,
                SyncPermissionAction.EXECUTE_READ_ONLY_QUERY)).isTrue();
        assertThat(ActorRole.ORDINARY_USER.canDiscoverMetadata()).isTrue();
    }

    @Test
    void ordinaryUserShouldNotGainSamplePreviewPermission() {
        assertThat(evaluator.canAccess(
                "ORDINARY_USER",
                SyncPermissionResource.DATASOURCE_METADATA,
                SyncPermissionAction.VIEW_SAMPLE)).isFalse();
        assertThat(ActorRole.ORDINARY_USER.canPreviewSampleRows()).isFalse();
    }
}
