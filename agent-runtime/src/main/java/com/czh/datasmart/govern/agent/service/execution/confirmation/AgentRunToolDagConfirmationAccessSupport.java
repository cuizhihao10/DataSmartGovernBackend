/**
 * @Author : Cui
 * @Date: 2026/06/01 22:20
 * @Description DataSmart Govern Backend - AgentRunToolDagConfirmationAccessSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.execution.confirmation;

import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventQueryAccessContext;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * DAG selected-node 确认记录的数据范围收口组件。
 *
 * <p>gateway + permission-admin 负责判断“当前角色能不能调用确认审计查询接口”，但进入 agent-runtime 后，
 * 服务层仍然要判断“最多能看到哪些确认记录”。确认记录虽然不保存工具参数，但它可以揭示用户确认了哪些工具节点、
 * 哪些命令进入 outbox、命中了哪版策略，因此不能只做入口级授权。</p>
 *
 * <p>这里复用 {@link AgentRuntimeEventQueryAccessContext} 是一个有意的工程折中：runtime event 和 confirmation
 * 都属于 Agent Runtime 的审计读模型，它们消费同一组 gateway Header（tenant、actor、role、dataScope、projectIds）。
 * 后续如果确认审计需要独立的审批态、导出态或更细字段级权限，可以再拆专用上下文，而不是现在重复一套 Header 解析。</p>
 */
@Component
public class AgentRunToolDagConfirmationAccessSupport {

    private static final Map<String, String> FALLBACK_SCOPE_BY_ROLE = Map.of(
            "ORDINARY_USER", "SELF",
            "PROJECT_OWNER", "PROJECT",
            "OPERATOR", "TENANT",
            "AUDITOR", "TENANT",
            "TENANT_ADMINISTRATOR", "TENANT",
            "PLATFORM_ADMINISTRATOR", "PLATFORM",
            "SERVICE_ACCOUNT", "PLATFORM"
    );

    /**
     * 判断某条确认记录是否在当前访问上下文允许的数据范围内。
     *
     * <p>该方法用于列表查询时的内存过滤，也可被未来 JDBC 查询用作最终防线。
     * 如果上下文缺少 tenantId/actorId，方法采用 fail-safe 策略返回 false，避免网关 Header 缺失时误放开确认记录。</p>
     *
     * @param record 候选确认记录
     * @param context gateway 透传并由 resolver 解析后的访问上下文
     * @return true 表示可以展示；false 表示应从查询结果中过滤
     */
    public boolean canRead(AgentRunToolDagConfirmationRecord record,
                           AgentRuntimeEventQueryAccessContext context) {
        if (record == null || context == null || !context.hasIdentity()) {
            return false;
        }
        return switch (resolveScopeLevel(context)) {
            case "PLATFORM" -> true;
            case "TENANT" -> sameTenant(record, context);
            case "PROJECT" -> sameTenant(record, context) && authorizedProject(record, context);
            case "SELF" -> sameTenant(record, context) && sameActor(record, context);
            default -> sameTenant(record, context);
        };
    }

    /**
     * 详情查询的越权处理入口。
     *
     * <p>列表查询遇到越权记录时直接过滤；详情查询如果 confirmationId 存在但不属于当前数据范围，
     * 应显式返回 403，避免调用方通过猜测 ID 判定记录是否存在。</p>
     */
    public void assertCanRead(AgentRunToolDagConfirmationRecord record,
                              AgentRuntimeEventQueryAccessContext context) {
        if (!canRead(record, context)) {
            throw new PlatformBusinessException(PlatformErrorCode.TENANT_SCOPE_DENIED,
                    "当前身份不能查看该 DAG selected-node 确认记录");
        }
    }

    private boolean sameTenant(AgentRunToolDagConfirmationRecord record,
                               AgentRuntimeEventQueryAccessContext context) {
        return record.tenantId() != null && record.tenantId().equals(context.tenantId());
    }

    private boolean sameActor(AgentRunToolDagConfirmationRecord record,
                              AgentRuntimeEventQueryAccessContext context) {
        return record.actorId() != null && record.actorId().equals(String.valueOf(context.actorId()));
    }

    private boolean authorizedProject(AgentRunToolDagConfirmationRecord record,
                                      AgentRuntimeEventQueryAccessContext context) {
        if (record.projectId() == null) {
            return false;
        }
        List<String> authorizedProjectIds = context.authorizedProjectIdsAsStrings();
        return authorizedProjectIds.contains(String.valueOf(record.projectId()));
    }

    private String resolveScopeLevel(AgentRuntimeEventQueryAccessContext context) {
        String explicitScope = context.normalizedDataScopeLevel();
        if (!explicitScope.isBlank()) {
            return explicitScope;
        }
        return FALLBACK_SCOPE_BY_ROLE.getOrDefault(context.normalizedRole(), "TENANT");
    }
}
