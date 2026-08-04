/**
 * @Author : Cui
 * @Date: 2026/08/04 00:00
 * @Description DataSmart Govern Backend - AgentDelegationRecord.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.session;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 用户向指定 Agent 发出的最小权限委托事实。
 *
 * <p>它不是用户权限的副本，更不是提权凭证。有效权限始终是用户 RBAC、资源授权、工具范围、
 * 本次委托范围和系统安全策略的交集。委托过期或撤销后，已有 sessionId 也不能继续执行工具。</p>
 */
public class AgentDelegationRecord {

    /** 委托仍可参与执行门禁判断；并不代表某次工具动作已经获批。 */
    public static final String ACTIVE = "ACTIVE";

    /** 委托已经被用户或控制面撤销，任何既有 session/run 都不能再依赖它执行工具。 */
    public static final String REVOKED = "REVOKED";

    /** 委托事实稳定 ID，贯穿会话、Run、工具审计与审批证据。 */
    private final String delegationId;

    /** 接受委托的 Agent 主体 ID，用于和登录用户主体形成双主体审计。 */
    private final String agentId;

    /** 发出委托的用户 actor ID；所有有效权限仍受该用户自身 RBAC 约束。 */
    private final String userActorId;

    /** 委托所属租户，不允许跨租户复用。 */
    private final Long tenantId;

    /** 委托所属项目，不允许在用户切换项目后继续复用。 */
    private final Long projectId;

    /** 会话已经显式绑定、可进入后续工具门禁的工具代码集合。 */
    private final Set<String> toolCodes;

    /**
     * 工具绑定声明的动作摘要。
     *
     * <p>当前字段主要用于低敏审计展示；真正执行仍由具体工具绑定、风险策略和下游权限再次判断，
     * 不能仅凭这里出现某个 action 就直接放行。</p>
     */
    private final Set<String> actions;

    /** 允许的目标范围，格式为 {@code targetService:resourceId} 或 {@code targetService:*}。 */
    private final Set<String> resourceScopes;

    /** 当前委托状态，至少支持 ACTIVE 与 REVOKED。 */
    private String status;

    /** 委托签发时间，也是缺省 updateTime。 */
    private final LocalDateTime issuedAt;

    /** 可选过期时间；为空时仍受会话生命周期和撤销状态控制。 */
    private LocalDateTime expiresAt;

    /** 实际撤销时间；未撤销时为空。 */
    private LocalDateTime revokedAt;

    /** 最近一次扩展工具范围或撤销委托的时间。 */
    private LocalDateTime updateTime;

    /**
     * 从新会话或数据库快照构建委托事实。
     *
     * <p>构造时会清理集合中的 null、空白和前后空格；核心身份字段为空会立即失败，避免创建无法审计的委托。
     * 状态为空时按 ACTIVE 兼容新建会话，签发时间为空时使用当前时间。</p>
     *
     * @param delegationId 委托稳定 ID
     * @param agentId Agent 主体 ID
     * @param userActorId 发出委托的用户 ID
     * @param tenantId 所属租户
     * @param projectId 所属项目
     * @param toolCodes 已委托工具代码
     * @param actions 已委托动作摘要
     * @param resourceScopes 已委托资源范围
     * @param status 当前状态
     * @param issuedAt 签发时间
     * @param expiresAt 过期时间
     * @param revokedAt 撤销时间
     * @param updateTime 最近更新时间
     */
    public AgentDelegationRecord(String delegationId,
                                 String agentId,
                                 String userActorId,
                                 Long tenantId,
                                 Long projectId,
                                 List<String> toolCodes,
                                 List<String> actions,
                                 List<String> resourceScopes,
                                 String status,
                                 LocalDateTime issuedAt,
                                 LocalDateTime expiresAt,
                                 LocalDateTime revokedAt,
                                 LocalDateTime updateTime) {
        this.delegationId = requireText(delegationId, "delegationId");
        this.agentId = requireText(agentId, "agentId");
        this.userActorId = requireText(userActorId, "userActorId");
        this.tenantId = tenantId;
        this.projectId = projectId;
        this.toolCodes = normalizedSet(toolCodes);
        this.actions = normalizedSet(actions);
        this.resourceScopes = normalizedSet(resourceScopes);
        this.status = status == null || status.isBlank() ? ACTIVE : status.trim().toUpperCase();
        this.issuedAt = issuedAt == null ? LocalDateTime.now() : issuedAt;
        this.expiresAt = expiresAt;
        this.revokedAt = revokedAt;
        this.updateTime = updateTime == null ? this.issuedAt : updateTime;
    }

    /**
     * 把一条已经通过业务校验的工具绑定收敛进委托范围。
     *
     * <p>该方法不会主动调用权限中心，因此只能在“工具绑定已经被允许”之后使用。它同步记录 toolCode、
     * allowedActions 和目标资源范围，并刷新 updateTime。资源 ID 为空时记录服务级通配范围，
     * 但后续工具策略和下游接口仍会再次鉴权。</p>
     *
     * @param binding 会话中新增加的受控工具绑定；null 时保持幂等并直接返回
     */
    public void grant(AgentToolBindingRecord binding) {
        if (binding == null) {
            return;
        }
        toolCodes.add(binding.toolCode());
        if (binding.allowedActions() != null) {
            binding.allowedActions().stream().filter(Objects::nonNull).map(String::trim)
                    .filter(value -> !value.isBlank()).forEach(actions::add);
        }
        String service = binding.targetService() == null || binding.targetService().isBlank()
                ? "platform"
                : binding.targetService().trim();
        resourceScopes.add(service + ":" + (binding.targetResourceId() == null ? "*" : binding.targetResourceId()));
        updateTime = LocalDateTime.now();
    }

    /**
     * 判断委托是否覆盖某次工具与目标资源组合。
     *
     * <p>检查顺序为：委托有效 -> toolCode 已绑定 -> 精确资源或服务级通配范围命中。这里不检查审批、
     * 风险等级、参数合法性和用户 RBAC，那些条件由执行 Guard 的其他步骤继续求交集。</p>
     *
     * @param toolCode 本次执行的工具代码
     * @param targetService 工具目标服务；为空时规范为 platform
     * @param targetResourceId 目标业务资源 ID
     * @return 委托当前有效且工具与资源范围都命中时返回 true
     */
    public boolean allows(String toolCode, String targetService, Long targetResourceId) {
        if (!active(LocalDateTime.now()) || toolCode == null || !toolCodes.contains(toolCode)) {
            return false;
        }
        String service = targetService == null || targetService.isBlank() ? "platform" : targetService.trim();
        return resourceScopes.contains(service + ":*")
                || resourceScopes.contains(service + ":" + targetResourceId);
    }

    /**
     * 在给定时间点判断委托是否有效。
     *
     * @param now 判断基准时间；为空时使用当前时间
     * @return 状态为 ACTIVE，且没有过期或过期时间晚于基准时间时返回 true
     */
    public boolean active(LocalDateTime now) {
        LocalDateTime reference = now == null ? LocalDateTime.now() : now;
        return ACTIVE.equals(status) && (expiresAt == null || expiresAt.isAfter(reference));
    }

    /**
     * 撤销委托并记录撤销时间。
     *
     * <p>撤销不会删除历史事实，便于后续解释某次 Run 当时为何被允许或为何在恢复阶段被拒绝。</p>
     */
    public void revoke() {
        status = REVOKED;
        revokedAt = LocalDateTime.now();
        updateTime = revokedAt;
    }

    /** @return 委托稳定 ID */
    public String getDelegationId() { return delegationId; }

    /** @return 接受委托的 Agent 主体 ID */
    public String getAgentId() { return agentId; }

    /** @return 发出委托的用户 actor ID */
    public String getUserActorId() { return userActorId; }

    /** @return 委托所属租户 ID */
    public Long getTenantId() { return tenantId; }

    /** @return 委托所属项目 ID */
    public Long getProjectId() { return projectId; }

    /** @return 不可修改的工具代码快照，避免调用方绕过 grant 直接扩展内部集合 */
    public List<String> getToolCodes() { return List.copyOf(toolCodes); }

    /** @return 不可修改的动作摘要快照 */
    public List<String> getActions() { return List.copyOf(actions); }

    /** @return 不可修改的资源范围快照 */
    public List<String> getResourceScopes() { return List.copyOf(resourceScopes); }

    /** @return 当前委托状态 */
    public String getStatus() { return status; }

    /** @return 委托签发时间 */
    public LocalDateTime getIssuedAt() { return issuedAt; }

    /** @return 过期时间，未设置时为空 */
    public LocalDateTime getExpiresAt() { return expiresAt; }

    /** @return 撤销时间，仍有效时为空 */
    public LocalDateTime getRevokedAt() { return revokedAt; }

    /** @return 委托最近更新时间 */
    public LocalDateTime getUpdateTime() { return updateTime; }

    /**
     * 把可空字符串列表规范化为保持插入顺序的去重集合。
     *
     * <p>保持插入顺序可以让数据库 JSON 与前端审计展示稳定，减少无意义的快照差异。</p>
     */
    private static Set<String> normalizedSet(List<String> values) {
        Set<String> result = new LinkedHashSet<>();
        if (values != null) {
            values.stream().filter(Objects::nonNull).map(String::trim)
                    .filter(value -> !value.isBlank()).forEach(result::add);
        }
        return result;
    }

    /**
     * 校验委托核心身份字段并返回清理后的文本。
     *
     * @param value 待校验值
     * @param field 用于异常提示的字段名
     * @return 去除前后空格后的非空文本
     */
    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value.trim();
    }
}
