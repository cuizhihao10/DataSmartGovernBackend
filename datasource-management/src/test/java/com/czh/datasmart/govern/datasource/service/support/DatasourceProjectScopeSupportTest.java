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
import static org.junit.jupiter.api.Assertions.assertNull;
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
                "101, 102, bad, -1, 102",
                "101:MANAGER,102:READER,bad:OWNER,102:VIEWER"
        );

        assertTrue(visibility.projectScopeEnforced());
        assertEquals(List.of(101L, 102L), visibility.authorizedProjectIds());
        assertEquals("MANAGER", visibility.projectRole(101L).orElseThrow());
        assertEquals("READER", visibility.projectRole(102L).orElseThrow());
        /*
         * 当前产品已经去掉用户可见的工作空间层级。即使旧前端、旧脚本或历史 E2E 仍然把 workspaceId
         * 传给后端，datasource-management 也不再把它传播到查询条件，否则用户在项目维度下创建的数据源
         * 可能因为不可见 workspace 过滤而“创建成功但列表看不到”。这里固定该兼容策略：读参数、忽略值。
         */
        assertNull(visibility.requestedWorkspaceId());
    }

    /**
     * 验证项目角色 Header 可以作为项目 ID Header 的安全兜底。
     *
     * <p>datasource-management 和 data-sync 必须保持同一套权限语义：
     * authorizedProjectIds 负责“可见哪些项目”，authorizedProjectRoles 负责“在项目里能做什么”。
     * 正常 gateway 会同时下发两者；如果灰度发布、测试请求或中间代理导致项目 ID Header 缺失，
     * 角色 Header 中仍然有 `projectId:role` 可信事实，因此可以推导出项目集合。
     * 这不会造成提权，因为 USE/MANAGE 动作还会继续检查角色强度。</p>
     */
    @Test
    void resolveVisibilityShouldDeriveAuthorizedProjectsFromRoleHeaderWhenIdHeaderMissing() {
        DatasourceProjectVisibility visibility = support.resolveVisibility(
                null,
                null,
                "PROJECT",
                "",
                "101:MANAGER,102:READER"
        );

        assertTrue(visibility.projectScopeEnforced());
        assertEquals(List.of(101L, 102L), visibility.authorizedProjectIds());
        assertEquals("MANAGER", visibility.projectRole(101L).orElseThrow());
        assertEquals("READER", visibility.projectRole(102L).orElseThrow());
    }

    @Test
    void resolveVisibilityShouldAllowRequestedProjectInsideAuthorizedSet() {
        DatasourceProjectVisibility visibility = support.resolveVisibility(
                102L,
                null,
                "PROJECT",
                "101,102",
                "101:OWNER,102:MANAGER"
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
                "101,102",
                "101:MANAGER,102:READER"
        ));
    }

    @Test
    void resolveVisibilityShouldKeepEmptyAuthorizedSetAsNoVisibility() {
        DatasourceProjectVisibility visibility = support.resolveVisibility(
                null,
                null,
                "PROJECT",
                "",
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
                "bad,101",
                "101:OWNER"
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
                List.of(
                        new com.czh.datasmart.govern.common.context.PlatformAuthorizedProjectRole(101L, "MANAGER"),
                        new com.czh.datasmart.govern.common.context.PlatformAuthorizedProjectRole(102L, "READER")),
                true
        );

        assertDoesNotThrow(() -> support.validateProjectReadable(101L, visibility, "数据源"));
        assertThrows(IllegalArgumentException.class,
                () -> support.validateProjectReadable(999L, visibility, "数据源"));
        assertThrows(IllegalArgumentException.class,
                () -> support.validateProjectReadable(null, visibility, "数据源"));
    }

    @Test
    void validateProjectActionsShouldRespectProjectRoles() {
        DatasourceProjectVisibility visibility = support.resolveVisibility(
                null,
                null,
                "PROJECT",
                "101,102,103",
                "101:OWNER,102:MANAGER,103:READER"
        );

        assertDoesNotThrow(() -> support.validateProjectManageable(101L, visibility, "数据源"));
        assertDoesNotThrow(() -> support.validateProjectManageable(102L, visibility, "数据源"));
        assertDoesNotThrow(() -> support.validateProjectReadable(103L, visibility, "数据源"));
        assertThrows(IllegalArgumentException.class,
                () -> support.validateProjectUsable(103L, visibility, "数据源"));
        assertThrows(IllegalArgumentException.class,
                () -> support.validateProjectManageable(103L, visibility, "数据源"));
    }
}
