/**
 * @Author : Cui
 * @Date: 2026/05/10 15:08
 * @Description DataSmart Govern Backend - DatasourceProjectScopeSupportTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.support;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * datasource-management 项目级可见范围单元测试。
 *
 * <p>这个测试不启动 Spring 容器，而是直接验证纯业务解析逻辑。
 * 这样做有两个好处：</p>
 * <p>1. 测试速度快，适合在每次重构 Controller 或权限 Header 协议时快速回归；</p>
 * <p>2. 测试目标更聚焦，失败时可以直接定位是 PROJECT 范围解析规则变了，而不是数据库、Web 层或配置启动问题。</p>
 *
 * <p>覆盖的核心安全语义：</p>
 * <p>1. PROJECT 范围下必须解析授权项目集合；</p>
 * <p>2. Header 中的脏值、负数、重复项目需要安全过滤；</p>
 * <p>3. 调用方指定未授权 projectId 时必须拒绝；</p>
 * <p>4. 空授权集合不能退化为全租户可见；</p>
 * <p>5. 非 PROJECT 范围暂不强制项目过滤，为后续 TENANT/PLATFORM 范围保留空间。</p>
 */
class DatasourceProjectScopeSupportTest {

    private final DatasourceProjectScopeSupport support = new DatasourceProjectScopeSupport();

    @Test
    void resolveVisibilityShouldParseAuthorizedProjectsWhenProjectScope() {
        DatasourceProjectVisibility visibility = support.resolveVisibility(
                null,
                301L,
                "PROJECT",
                "101, 102, bad, -1, 102"
        );

        assertTrue(visibility.projectScopeEnforced());
        assertEquals(List.of(101L, 102L), visibility.authorizedProjectIds());
        assertEquals(301L, visibility.requestedWorkspaceId());
    }

    @Test
    void resolveVisibilityShouldAllowRequestedProjectInsideAuthorizedSet() {
        DatasourceProjectVisibility visibility = support.resolveVisibility(
                102L,
                null,
                "PROJECT",
                "101,102"
        );

        assertTrue(visibility.projectScopeEnforced());
        assertEquals(102L, visibility.requestedProjectId());
    }

    @Test
    void resolveVisibilityShouldRejectRequestedProjectOutsideAuthorizedSet() {
        assertThrows(IllegalArgumentException.class, () -> support.resolveVisibility(
                999L,
                null,
                "PROJECT",
                "101,102"
        ));
    }

    @Test
    void resolveVisibilityShouldKeepEmptyAuthorizedSetAsNoVisibility() {
        DatasourceProjectVisibility visibility = support.resolveVisibility(
                null,
                null,
                "PROJECT",
                ""
        );

        assertTrue(visibility.projectScopeEnforced());
        assertTrue(visibility.authorizedProjectIds().isEmpty());
    }

    @Test
    void resolveVisibilityShouldNotEnforceProjectFilterForTenantScope() {
        DatasourceProjectVisibility visibility = support.resolveVisibility(
                null,
                null,
                "TENANT",
                "bad,101"
        );

        assertFalse(visibility.projectScopeEnforced());
        assertTrue(visibility.authorizedProjectIds().isEmpty());
    }

    @Test
    void validateProjectReadableShouldRejectUnauthorizedResourceWhenProjectScope() {
        DatasourceProjectVisibility visibility = new DatasourceProjectVisibility(
                null,
                null,
                List.of(101L, 102L),
                true
        );

        assertDoesNotThrow(() -> support.validateProjectReadable(101L, visibility, "数据源"));
        assertThrows(IllegalArgumentException.class,
                () -> support.validateProjectReadable(999L, visibility, "数据源"));
        assertThrows(IllegalArgumentException.class,
                () -> support.validateProjectReadable(null, visibility, "数据源"));
    }
}
