/**
 * @Author : Cui
 * @Date: 2026/05/10 15:20
 * @Description DataSmart Govern Backend - QualityProjectScopeSupportTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.service.support;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * data-quality 项目级可见范围单元测试。
 *
 * <p>质量模块的权限风险比普通配置列表更高，因为它不仅保存“规则定义”，还会保存“检测结果”和“异常样本”。
 * 异常样本里可能包含主键摘要、字段值、样本载荷和清洗建议，一旦 PROJECT 范围解释错误，
 * 项目 A 的成员就可能看到项目 B 的业务数据问题，这会同时影响安全、合规和客户信任。</p>
 *
 * <p>本测试只验证 `QualityProjectScopeSupport` 这种纯逻辑组件，不启动 Spring 容器、不连接 MySQL。
 * 这样可以把安全语义固定成很快的回归测试：后续无论 Controller 如何拆分、Service 如何重构，
 * 只要 PROJECT 范围的基础契约被破坏，测试就会第一时间失败。</p>
 */
class QualityProjectScopeSupportTest {

    private final QualityProjectScopeSupport support = new QualityProjectScopeSupport();

    @Test
    void resolveVisibilityShouldParseAuthorizedProjectsForProjectScope() {
        QualityProjectVisibility visibility = support.resolveVisibility(
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
        QualityProjectVisibility visibility = support.resolveVisibility(
                102L,
                302L,
                "PROJECT",
                "101,102"
        );

        assertTrue(visibility.projectScopeEnforced());
        assertEquals(102L, visibility.requestedProjectId());
        assertEquals(302L, visibility.requestedWorkspaceId());
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
    void resolveVisibilityShouldTreatEmptyAuthorizedHeaderAsNoVisibleProjects() {
        QualityProjectVisibility visibility = support.resolveVisibility(
                null,
                null,
                "PROJECT",
                ""
        );

        assertTrue(visibility.projectScopeEnforced());
        assertTrue(visibility.authorizedProjectIds().isEmpty());
    }

    @Test
    void resolveVisibilityShouldNotEnforceProjectFilterOutsideProjectScope() {
        QualityProjectVisibility visibility = support.resolveVisibility(
                null,
                null,
                "TENANT",
                "101,102"
        );

        assertFalse(visibility.projectScopeEnforced());
        assertTrue(visibility.authorizedProjectIds().isEmpty());
    }

    @Test
    void validateProjectReadableShouldRejectUnauthorizedQualityResource() {
        QualityProjectVisibility visibility = new QualityProjectVisibility(
                null,
                null,
                List.of(101L, 102L),
                true
        );

        assertDoesNotThrow(() -> support.validateProjectReadable(101L, visibility, "质量规则"));
        assertThrows(IllegalArgumentException.class,
                () -> support.validateProjectReadable(999L, visibility, "质量规则"));
        assertThrows(IllegalArgumentException.class,
                () -> support.validateProjectReadable(null, visibility, "质量规则"));
    }
}
