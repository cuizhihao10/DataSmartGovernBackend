/**
 * @Author : Cui
 * @Date: 2026/05/10 13:45
 * @Description DataSmart Govern Backend - SyncDataScopeSupportTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.common.context.PlatformAuthorizedProjectRole;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * `SyncDataScopeSupport` 的 PROJECT 数据范围测试。
 *
 * <p>这组测试不启动 Spring 容器，也不连接数据库，只验证“权限上下文如何被翻译成业务查询范围”。
 * 这正是 PROJECT 数据范围最容易回归的地方：只要这里退化成租户范围，项目负责人就可能看到同租户其他项目数据。
 */
class SyncDataScopeSupportTest {

    private final SyncDataScopeSupport support = new SyncDataScopeSupport();

    /**
     * 验证 gateway 明确透传 PROJECT 范围且没有请求 projectId 时，后端会自动使用授权项目集合。
     *
     * <p>这是 3.05 的核心商业化目标：项目负责人不应该依赖前端主动传 projectId 才能安全过滤。
     * 后端看到 `dataScopeLevel=PROJECT` 和 `authorizedProjectIds=[101,102]` 后，应生成可执行的项目集合约束。
     */
    @Test
    void shouldEnforceAuthorizedProjectIdsWhenGatewayReturnsProjectScope() {
        SyncActorContext actor = projectOwnerWithProjects(List.of(101L, 102L));

        SyncDataVisibility visibility = support.resolveVisibility(null, null, null, actor);

        assertEquals(7L, visibility.tenantId());
        assertEquals(List.of(101L, 102L), visibility.authorizedProjectIds());
        assertTrue(visibility.projectScopeEnforced());
        assertFalse(visibility.selfOnly());
    }

    /**
     * 验证项目角色 Header 可以作为项目 ID Header 的安全兜底。
     *
     * <p>真实生产链路中，gateway 会同时写入 authorizedProjectIds 和 authorizedProjectRoles。
     * 但在灰度发布、测试构造 Header 或某些中间代理裁剪 Header 时，可能暂时只剩 `101:MANAGER` 这类角色快照。
     * 角色快照仍然来自 permission-admin，不是用户 request body，因此可以从中推导 projectId；
     * 这样只读查询不会误变成空列表，写入动作仍会继续检查 MANAGER/OWNER/SERVICE。</p>
     */
    @Test
    void shouldDeriveAuthorizedProjectIdsFromProjectRolesWhenProjectIdHeaderIsMissing() {
        SyncActorContext actor = projectScopedActorWithRoles(
                List.of(),
                List.of(
                        new PlatformAuthorizedProjectRole(101L, "MANAGER"),
                        new PlatformAuthorizedProjectRole(102L, "READER")));

        SyncDataVisibility visibility = support.resolveVisibility(null, null, null, actor);

        assertTrue(visibility.projectScopeEnforced());
        assertEquals(List.of(101L, 102L), visibility.authorizedProjectIds());
        assertEquals("MANAGER", visibility.authorizedProjectRoles().get(0).projectRole());
        assertEquals("READER", visibility.authorizedProjectRoles().get(1).projectRole());
    }

    /**
     * 验证请求指定的 projectId 必须属于授权集合。
     *
     * <p>如果用户传入未授权 projectId，不能简单返回空列表，因为这可能掩盖越权探测。
     * 当前策略是直接抛出业务异常，让审计和调用方都能知道这是一次越权访问尝试。
     */
    @Test
    void shouldRejectRequestedProjectOutsideAuthorizedSet() {
        SyncActorContext actor = projectOwnerWithProjects(List.of(101L, 102L));

        assertThrows(PlatformBusinessException.class,
                () -> support.resolveVisibility(null, 999L, null, actor));
    }

    /**
     * 验证请求指定授权 projectId 时允许通过。
     *
     * <p>当 requestedProjectId 已经明确且属于授权集合，Service 层只需要追加 `project_id = requestedProjectId`，
     * 不需要再追加 `IN authorizedProjectIds`，因此 authorizedProjectIds 可以为空，避免重复条件。
     */
    @Test
    void shouldAllowRequestedProjectInsideAuthorizedSet() {
        SyncActorContext actor = projectOwnerWithProjects(List.of(101L, 102L));

        SyncDataVisibility visibility = support.resolveVisibility(null, 101L, null, actor);

        assertEquals(101L, visibility.projectId());
        assertEquals(List.of(), visibility.authorizedProjectIds());
        assertTrue(visibility.projectScopeEnforced());
    }

    /**
     * 验证空授权项目集合不会退回租户范围。
     *
     * <p>空项目集合在安全语义上表示“本次 PROJECT 范围没有可见项目”。
     * Service 层后续会把它翻译为恒 false 条件；这里先保证可见性对象保留 enforced=true 和空集合。
     */
    @Test
    void shouldKeepProjectScopeEnforcedWhenAuthorizedProjectSetIsEmpty() {
        SyncActorContext actor = projectOwnerWithProjects(List.of());

        SyncDataVisibility visibility = support.resolveVisibility(null, null, null, actor);

        assertTrue(visibility.projectScopeEnforced());
        assertEquals(List.of(), visibility.authorizedProjectIds());
    }

    /**
     * 验证详情接口也必须检查资源所属项目。
     *
     * <p>列表接口有查询条件保护，但详情接口通常只有资源 ID。
     * 如果不校验 resourceProjectId，攻击者可能通过猜测 ID 读取同租户其他项目的模板、任务或事故。
     */
    @Test
    void shouldRejectDetailResourceWhoseProjectIsNotAuthorized() {
        SyncActorContext actor = projectOwnerWithProjects(List.of(101L, 102L));

        assertThrows(PlatformBusinessException.class,
                () -> support.validateOwnedReadable(7L, 999L, 1001L, actor, "同步模板"));
    }

    /**
     * 验证授权项目内的详情读取可以通过。
     */
    @Test
    void shouldAllowDetailResourceWhoseProjectIsAuthorized() {
        SyncActorContext actor = projectOwnerWithProjects(List.of(101L, 102L));

        assertDoesNotThrow(() -> support.validateOwnedReadable(7L, 101L, 2002L, actor, "同步模板"));
    }

    /**
     * 验证 PROJECT 范围下创建资源必须显式落到授权项目。
     *
     * <p>这是写入链路和读取链路最大的区别：
     * 读取时没有 projectId 可以被解释为“查我能看到的所有项目”，Service 层会追加 `project_id IN (...)`；
     * 但写入时没有 projectId 就意味着资源没有明确项目归属，后续审批、配额、审计和执行器调度都无法正确判断边界。
     */
    @Test
    void shouldRejectProjectScopedWriteWhenProjectIdIsMissing() {
        SyncActorContext actor = projectOwnerWithProjects(List.of(101L, 102L));

        assertThrows(PlatformBusinessException.class,
                () -> support.validateProjectWritable(7L, null, null, actor, "同步模板"));
    }

    /**
     * 验证 PROJECT 范围下写入未授权项目会被拒绝。
     *
     * <p>这个测试保护的是“创建模板/任务”这类高风险入口。
     * 如果这里退化，用户虽然可能在列表中看不到未授权项目的数据，但未授权模板已经被写入数据库，
     * 后续一旦被调度或由管理员误操作，就可能形成跨项目数据移动风险。
     */
    @Test
    void shouldRejectProjectScopedWriteOutsideAuthorizedProjects() {
        SyncActorContext actor = projectOwnerWithProjects(List.of(101L, 102L));

        assertThrows(PlatformBusinessException.class,
                () -> support.validateProjectWritable(7L, 999L, null, actor, "同步模板"));
    }

    /**
     * 验证 PROJECT 范围下写入授权项目可以通过。
     */
    @Test
    void shouldAllowProjectScopedWriteInsideAuthorizedProjects() {
        SyncActorContext actor = projectOwnerWithProjects(List.of(101L, 102L));

        assertDoesNotThrow(() -> support.validateProjectWritable(7L, 101L, null, actor, "同步模板"));
    }

    /**
     * 创建同步模板/任务时应优先使用 gateway 重建的当前项目，并且不再写入 workspace。
     *
     * <p>这条用例和 gateway 项目 Header 校验配套：gateway 已经确认 {@code X-DataSmart-Project-Id=205}
     * 属于当前用户可访问范围，data-sync 领域层就应该把新资源写入该项目。
     * 如果请求体里又带了不同 projectId，说明页面上下文和表单参数冲突，应拒绝；workspace 则不再作为用户可见归属，
     * 即使旧调用仍传 workspace，也必须返回 null。</p>
     */
    @Test
    void createScopeShouldUseGatewayProjectHeaderAndIgnoreWorkspace() {
        SyncActorContext actor = new SyncActorContext(
                7L,
                205L,
                10001L,
                1001L,
                "PROJECT_OWNER",
                "trace-create-project",
                "PROJECT",
                "project_id IN ${authorizedProjectIds}",
                List.of(205L),
                false);

        assertEquals(205L, support.resolveProjectForCreate(null, actor));
        assertEquals(205L, support.resolveProjectForCreate(205L, actor));
        assertThrows(PlatformBusinessException.class,
                () -> support.resolveProjectForCreate(999L, actor));
        assertNull(support.resolveWorkspaceForCreate(10001L, actor));
    }

    /**
     * 验证迁移期的角色兜底不会强制空项目集合。
     *
     * <p>本地调试、后台调度或旧调用点可能只有 actorRole=PROJECT_OWNER，但没有 gateway 透传的 dataScopeLevel。
     * 此时系统先保持兼容，不强制 `project_id IN empty`，避免还没经过网关的内部调用全部变成空结果。
     * 生产请求仍应经过 gateway，由 permission-admin 明确返回 PROJECT 范围和授权项目集合。
     */
    @Test
    void shouldNotEnforceProjectSetForRoleFallbackWithoutGatewayScope() {
        SyncActorContext actor = new SyncActorContext(7L, 1001L, "PROJECT_OWNER", "trace-fallback");

        SyncDataVisibility visibility = support.resolveVisibility(null, null, null, actor);

        assertEquals("PROJECT", visibility.scopeLevel());
        assertFalse(visibility.projectScopeEnforced());
        assertEquals(List.of(), visibility.authorizedProjectIds());
    }

    /**
     * 验证 PROJECT 范围下 READER 只能查看，不能执行写入或管理动作。
     *
     * <p>该用例保护的是后端兜底权限：前端可以根据菜单/按钮权限隐藏“运行、编辑、删除”等入口，
     * 但真正的安全边界必须在后端。只要用户在项目内不是 MANAGER/OWNER/SERVICE，
     * 就算他能看到项目内任务，也不能通过直调接口改变任务生命周期。</p>
     */
    @Test
    void shouldRejectProjectReaderWhenManagingProjectResource() {
        SyncActorContext actor = projectScopedActorWithRoles(
                List.of(101L),
                List.of(new PlatformAuthorizedProjectRole(101L, "READER")));

        assertThrows(PlatformBusinessException.class,
                () -> support.validateProjectManageable(7L, 101L, null, actor, "同步任务"));
    }

    private SyncActorContext projectOwnerWithProjects(List<Long> projectIds) {
        List<PlatformAuthorizedProjectRole> projectRoles = projectIds.stream()
                .map(projectId -> new PlatformAuthorizedProjectRole(projectId, "MANAGER"))
                .toList();
        return projectScopedActorWithRoles(projectIds, projectRoles);
    }

    private SyncActorContext projectScopedActorWithRoles(List<Long> projectIds,
                                                         List<PlatformAuthorizedProjectRole> projectRoles) {
        return new SyncActorContext(
                7L,
                null,
                null,
                1001L,
                "PROJECT_OWNER",
                "trace-project",
                "PROJECT",
                "project_id IN ${actorProjectIds}",
                projectIds,
                projectRoles,
                false);
    }
}
