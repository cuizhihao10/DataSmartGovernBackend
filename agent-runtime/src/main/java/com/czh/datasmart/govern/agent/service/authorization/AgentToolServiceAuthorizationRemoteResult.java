/**
 * @Author : Cui
 * @Date: 2026/05/31 23:59
 * @Description DataSmart Govern Backend - AgentToolServiceAuthorizationRemoteResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.authorization;

import java.util.List;

/**
 * permission-admin evaluate 接口返回给 Agent Runtime 的授权结果快照。
 *
 * <p>该对象只保留 Agent Runtime 执行预检真正需要的字段，不泄露 permission-admin 内部实体。
 * 如果后续权限中心增加审批链、策略版本号、缓存命中信息或租户级配额，也应优先在这里扩展为稳定契约，
 * 而不是让上层 DAG preview 直接解析远端原始响应。</p>
 *
 * @param allowed 远端是否允许本次动作。
 * @param reason 远端判定原因。
 * @param routeEffect 命中的路由策略效果，例如 ALLOW/DENY。
 * @param dataScopeLevel 远端计算出的数据范围等级。
 * @param authorizedProjectIds 远端物化出的可访问项目集合。
 * @param approvalRequired 当前动作是否还需要审批。
 */
public record AgentToolServiceAuthorizationRemoteResult(
        Boolean allowed,
        String reason,
        String routeEffect,
        String dataScopeLevel,
        List<Long> authorizedProjectIds,
        Boolean approvalRequired,
        String policyVersion,
        Boolean delegated,
        String delegationEvidence
) {
}
