/**
 * @Author : Cui
 * @Date: 2026/05/31 23:59
 * @Description DataSmart Govern Backend - AgentToolServiceAuthorizationPreviewView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * Agent 工具服务间授权预览视图。
 *
 * <p>该视图回答的是：“如果 Agent Runtime 下一步要代表某个 actor 推进这个工具节点，
 * 当前是否已经具备服务间授权依据？” 它不会执行工具，也不会修改 permission-admin 策略。
 * 设计成独立 DTO，是为了让前端、审计台、未来 DAG worker 都能明确区分：
 * DAG ready、工具策略 ready、异步 command ready、服务间授权 ready 是四件不同的事。</p>
 *
 * @param enabled 授权预检开关是否启用。
 * @param mode 当前使用的授权预检模式，例如 LOCAL_PREVIEW 或 PERMISSION_ADMIN_EVALUATE。
 * @param decision 授权预检结论，保留未评估、本地预览通过、远端拒绝、远端不可用等细粒度状态。
 * @param allowed 当前预检是否认为可以继续推进。未评估时通常为 false，避免被误读成允许。
 * @param enforced 当前结论是否会影响 preview 的执行候选动作。默认不强制，只展示；真实 worker 前应强制。
 * @param serviceAccountCode Agent Runtime 使用的服务账号编码，便于审计解释。
 * @param serviceAccountActorId permission-admin evaluate 使用的服务账号 actorId。
 * @param serviceAccountRole 服务账号角色编码。
 * @param representedActorId 被 Agent 代表的人类 actor 或上游调用方 ID。
 * @param tenantId 工具所属租户边界。
 * @param projectId 工具所属项目边界。
 * @param workspaceId 工具所属工作空间边界。
 * @param resourceType 映射给 permission-admin 的资源类型。
 * @param targetService 工具目标服务。
 * @param targetEndpoint 工具目标端点或权限判定路径。
 * @param targetResourceId 工具目标资源 ID，例如 datasourceId、taskId、syncTaskId。
 * @param requiredActions 真实执行前需要具备的动作权限集合。
 * @param reasons 授权预检解释，面向学习、审计和排障。
 * @param recommendedActions 推荐的后续处理动作。
 */
public record AgentToolServiceAuthorizationPreviewView(
        Boolean enabled,
        String mode,
        String decision,
        Boolean allowed,
        Boolean enforced,
        String serviceAccountCode,
        Long serviceAccountActorId,
        String serviceAccountRole,
        String representedActorId,
        Long tenantId,
        Long projectId,
        Long workspaceId,
        String resourceType,
        String targetService,
        String targetEndpoint,
        Long targetResourceId,
        List<String> requiredActions,
        List<String> reasons,
        List<String> recommendedActions
) {
}
