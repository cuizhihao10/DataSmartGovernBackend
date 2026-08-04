/**
 * @Author : Cui
 * @Date: 2026/08/04 00:00
 * @Description DataSmart Govern Backend - AgentSessionAccessContext.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.session;

import java.util.Set;

/**
 * Gateway 清理客户端伪造 Header 后注入的当前用户访问上下文。
 *
 * <p>该对象描述“谁在访问”，不描述 Agent 被允许执行什么工具。工具权限必须继续与 session delegation、
 * 工具策略和资源授权求交集。把上下文封装为不可变 record，可以避免控制器在多层调用之间遗漏某个隔离维度。</p>
 *
 * @param tenantId 当前用户所属租户
 * @param projectId 当前页面选择且已经过授权的项目
 * @param actorId 当前登录用户 ID
 * @param actorRole 当前请求的主角色，用于判断平台/租户管理只读能力
 */
public record AgentSessionAccessContext(Long tenantId,
                                        Long projectId,
                                        String actorId,
                                        String actorRole) {

    private static final Set<String> PRIVILEGED_READ_ROLES = Set.of(
            "PLATFORM_ADMIN", "PLATFORM_ADMINISTRATOR", "TENANT_ADMIN", "TENANT_ADMINISTRATOR",
            "OPERATOR", "AUDITOR");

    /**
     * 判断调用方是否具备跨租户、跨项目的只读平台审计能力。
     *
     * <p>这里只用于会话查询可见性，不授予会话修改或工具执行权限。即使平台管理员能够查看会话，
     * {@code AgentSessionService} 仍要求会话发起人在原租户/项目内才能继续对话。</p>
     *
     * @return 角色是 PLATFORM_ADMIN 或 PLATFORM_ADMINISTRATOR 时返回 true
     */
    public boolean platformAdministrator() {
        if (actorRole == null) {
            return false;
        }
        String role = actorRole.trim().toUpperCase();
        return "PLATFORM_ADMIN".equals(role) || "PLATFORM_ADMINISTRATOR".equals(role);
    }

    /**
     * 判断调用方是否可以执行管理或审计目的的扩展读取。
     *
     * <p>平台管理员、租户管理员、运维和审计角色可以读取权限范围内的他人会话；普通用户始终只能读取自己。
     * 该方法故意不命名为 {@code canManage}，避免调用方误把只读能力用于写操作。</p>
     *
     * @return 当前角色属于受控只读角色集合时返回 true
     */
    public boolean privilegedRead() {
        return actorRole != null && PRIVILEGED_READ_ROLES.contains(actorRole.trim().toUpperCase());
    }
}
