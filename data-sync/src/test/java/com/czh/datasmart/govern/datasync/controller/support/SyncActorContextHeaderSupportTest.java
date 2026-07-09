/**
 * @Author : Cui
 * @Date: 2026/05/10 13:43
 * @Description DataSmart Govern Backend - SyncActorContextHeaderSupportTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.support;

import com.czh.datasmart.govern.common.context.PlatformAuthorizedProjectRole;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * `SyncActorContextHeaderSupport` 的 Header 解析测试。
 *
 * <p>PROJECT 数据范围的项目集合来自 gateway 透传的 HTTP Header。
 * 这类解析逻辑看起来很小，但它处在安全边界上：如果解析错误，可能导致项目负责人看不到自己的项目，
 * 更严重时可能导致非法项目 ID 被当成授权集合。因此这里用纯单元测试把解析规则固定下来。
 */
class SyncActorContextHeaderSupportTest {

    /**
     * 验证授权项目 Header 的容错解析规则。
     *
     * <p>真实网关透传的 Header 理论上应该只包含正整数，但生产环境里仍要考虑代理、手工联调、灰度脚本等场景。
     * 当前设计是：跳过空片段、非数字片段、非正数，并对重复 ID 去重。
     * 这样坏片段不会让整个请求 500，同时也不会把非法值传入 MyBatis 查询条件。
     */
    @Test
    void shouldParseAuthorizedProjectIdsFromTrustedGatewayHeader() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(PlatformContextHeaders.DATA_SCOPE_LEVEL, "PROJECT");
        headers.set(PlatformContextHeaders.DATA_SCOPE_EXPRESSION, "project_id IN ${actorProjectIds}");
        headers.set(PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, "101, 102, abc, 0, -7, 102, 205");
        headers.set(PlatformContextHeaders.APPROVAL_REQUIRED, "true");

        SyncActorContext context = SyncActorContextHeaderSupport.fromHeaders(
                7L, 1001L, "PROJECT_OWNER", "trace-001", headers);

        assertEquals("PROJECT", context.dataScopeLevel());
        assertEquals("project_id IN ${actorProjectIds}", context.dataScopeExpression());
        assertEquals(List.of(101L, 102L, 205L), context.authorizedProjectIds());
        assertTrue(context.approvalRequired());
    }

    /**
     * 验证缺失授权项目 Header 时返回空集合。
     *
     * <p>空集合和 null 的业务语义不同：空集合表示“本次请求没有任何授权项目”，
     * Service 层可以稳定地把它收敛为无数据；null 则容易导致调用方忘记判断，进而产生空指针或权限降级。
     */
    @Test
    void shouldUseEmptyProjectListWhenHeaderIsMissing() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(PlatformContextHeaders.DATA_SCOPE_LEVEL, "PROJECT");

        SyncActorContext context = SyncActorContextHeaderSupport.fromHeaders(
                7L, 1001L, "PROJECT_OWNER", "trace-002", headers);

        assertEquals(List.of(), context.authorizedProjectIds());
        assertFalse(context.approvalRequired());
    }

    /**
     * 验证历史工作空间 Header 已经从 data-sync 用户侧链路退场。
     *
     * <p>真实问题来自前端截图：网关/开发身份仍把 {@code X-DataSmart-Workspace-Id=workspace-a}
     * 带给 data-sync，而旧实现会把它当 Long 解析，导致“保存并进入对象映射”时源端、目标端元数据发现各报一次错。
     * 当前产品口径已经只保留项目，不再让用户选择工作空间；因此该 Header 必须被安全忽略，而不是继续参与解析、查询或创建。</p>
     */
    /**
     * 验证项目内角色 Header 的解析、归一化和去重。
     *
     * <p>项目 ID 集合只能回答“能看哪些项目”，项目角色集合才能回答“在项目内能不能管理资源”。
     * 因此 data-sync 的 Header 解析必须同时消费 `X-DataSmart-Authorized-Project-Roles`，
     * 并把历史角色 MEMBER/MAINTAINER/VIEWER 归一成 MANAGER/READER，避免每个业务模块各自解释旧角色。</p>
     */
    @Test
    void shouldParseAuthorizedProjectRolesFromTrustedGatewayHeader() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(PlatformContextHeaders.DATA_SCOPE_LEVEL, "PROJECT");
        headers.set(PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, "101,102,205");
        headers.set(PlatformContextHeaders.AUTHORIZED_PROJECT_ROLES,
                "101:reader,102:member,102:READER,bad,205:OWNER,0:MANAGER,abc:OWNER");

        SyncActorContext context = SyncActorContextHeaderSupport.fromHeaders(
                7L, 1001L, "PROJECT_OWNER", "trace-project-roles", headers);

        assertEquals(List.of(
                new PlatformAuthorizedProjectRole(101L, "READER"),
                new PlatformAuthorizedProjectRole(102L, "MANAGER"),
                new PlatformAuthorizedProjectRole(205L, "OWNER")
        ), context.authorizedProjectRoles());
    }

    @Test
    void shouldIgnoreLegacyWorkspaceHeaderWhenItIsNotNumeric() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(PlatformContextHeaders.PROJECT_ID, "101");
        headers.set(PlatformContextHeaders.WORKSPACE_ID, "workspace-a");

        SyncActorContext context = SyncActorContextHeaderSupport.fromHeaders(
                10L, 1001L, "PROJECT_OWNER", "trace-workspace-legacy", headers);

        assertEquals(101L, context.projectId());
        assertNull(context.workspaceId());
    }
}
