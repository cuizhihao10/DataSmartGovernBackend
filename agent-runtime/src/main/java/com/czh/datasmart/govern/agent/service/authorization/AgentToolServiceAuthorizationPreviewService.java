/**
 * @Author : Cui
 * @Date: 2026/05/31 23:59
 * @Description DataSmart Govern Backend - AgentToolServiceAuthorizationPreviewService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.authorization;

import com.czh.datasmart.govern.agent.config.AgentToolServiceAuthorizationProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolExecutionPolicyItemView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionAuditView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolPlanDagNodeView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolServiceAuthorizationPreviewView;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionMode;
import com.czh.datasmart.govern.agent.model.AgentToolServiceAuthorizationDecision;
import com.czh.datasmart.govern.agent.model.AgentToolServiceAuthorizationMode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Agent 工具服务间授权预览服务。
 *
 * <p>该服务是 DAG execution preview 和 permission-admin 之间的“防腐层”。
 * DAG preview 只知道某个节点 ready、是什么工具、风险如何；permission-admin 只知道角色、路由、
 * 资源类型和动作。这里负责把 Agent 工具审计事实翻译成权限中心可以理解的授权问题。</p>
 *
 * <p>注意：本类仍然只做预览，不改变工具状态，也不替代真实执行入口的二次校验。
 * 真正 worker 落地时，应该在执行前再次调用同一服务或同一策略链，避免 preview 与执行之间状态变化造成越权。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentToolServiceAuthorizationPreviewService {

    private final AgentToolServiceAuthorizationProperties properties;
    private final PermissionAdminServiceAuthorizationClient permissionAdminClient;

    /**
     * 对单个 DAG 节点做服务间授权预览。
     *
     * @param audit 工具审计视图，提供租户、项目、actor、目标服务、目标端点等事实。
     * @param node DAG 节点视图，提供工具编码、执行模式和节点 ID。
     * @param policy 执行策略视图，提供只读、风险、幂等等策略判断。
     * @return 可展示、可审计、可被后续 worker 复用的授权预览结果。
     */
    public AgentToolServiceAuthorizationPreviewView preview(AgentToolExecutionAuditView audit,
                                                            AgentToolPlanDagNodeView node,
                                                            AgentRunToolExecutionPolicyItemView policy) {
        AuthorizationContext context = AuthorizationContext.from(audit, node, policy);
        if (!Boolean.TRUE.equals(properties.getEnabled())) {
            return disabled(context);
        }
        if (mode() == AgentToolServiceAuthorizationMode.PERMISSION_ADMIN_EVALUATE) {
            return permissionAdminPreview(context);
        }
        return localPreview(context);
    }

    private AgentToolServiceAuthorizationPreviewView disabled(AuthorizationContext context) {
        List<String> reasons = new ArrayList<>();
        reasons.add("Agent 工具服务间授权预检未启用，当前 DAG preview 不能证明服务账号具备真实执行权限。");
        List<String> actions = new ArrayList<>();
        actions.add("进入真实 DAG worker 前，请启用 datasmart.agent-runtime.service-authorization.enabled，并选择 LOCAL_PREVIEW 或 PERMISSION_ADMIN_EVALUATE 模式。");
        actions.add("生产环境建议接入 permission-admin evaluate，并为 SERVICE_ACCOUNT 配置最小权限策略。");
        return buildView(context, AgentToolServiceAuthorizationDecision.NOT_EVALUATED, false, reasons, actions);
    }

    private AgentToolServiceAuthorizationPreviewView localPreview(AuthorizationContext context) {
        List<String> reasons = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        List<String> missingFields = missingRequiredFields(context);
        if (!missingFields.isEmpty()) {
            reasons.add("本地授权预览发现关键上下文缺失：" + String.join(", ", missingFields) + "。");
            actions.add("请先让 Python ToolPlan、Java ingestion 或工具目录补齐租户、项目、actor、工具动作和目标服务信息，再进入真实执行。");
            return buildView(context, AgentToolServiceAuthorizationDecision.LOCAL_PREVIEW_REJECTED, false, reasons, actions);
        }
        reasons.add("本地授权预览通过：当前工具计划携带了服务账号、租户、项目、actor、目标服务和动作集合。");
        reasons.add("该结论只代表上下文结构完整，不等同于 permission-admin 已经授权。");
        actions.add("真实执行前仍建议切换到 PERMISSION_ADMIN_EVALUATE，由权限中心根据角色、路由策略和数据范围做最终判定。");
        return buildView(context, AgentToolServiceAuthorizationDecision.LOCAL_PREVIEW_ALLOWED, true, reasons, actions);
    }

    private AgentToolServiceAuthorizationPreviewView permissionAdminPreview(AuthorizationContext context) {
        List<String> reasons = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        List<String> missingFields = missingRequiredFields(context);
        if (!missingFields.isEmpty()) {
            reasons.add("调用 permission-admin 前发现关键上下文缺失：" + String.join(", ", missingFields) + "。");
            actions.add("请先补齐授权上下文，否则权限中心无法做出可靠判定。");
            return buildView(context, AgentToolServiceAuthorizationDecision.LOCAL_PREVIEW_REJECTED, false, reasons, actions);
        }
        try {
            return evaluateRequiredActions(context, reasons, actions);
        } catch (RestClientException exception) {
            reasons.add("permission-admin 授权预检不可用：" + exception.getMessage());
            if (Boolean.TRUE.equals(properties.getFailClosedWhenRemoteUnavailable())) {
                actions.add("当前配置为 fail-closed，真实执行应阻断该节点，并触发权限中心可用性告警。");
            } else {
                actions.add("当前配置允许远端不可用时不强制阻断，但生产环境不建议这样配置。");
            }
            return buildView(
                    context,
                    AgentToolServiceAuthorizationDecision.PERMISSION_ADMIN_UNAVAILABLE,
                    !Boolean.TRUE.equals(properties.getFailClosedWhenRemoteUnavailable()),
                    reasons,
                    actions
            );
        }
    }

    private AgentToolServiceAuthorizationPreviewView evaluateRequiredActions(AuthorizationContext context,
                                                                            List<String> reasons,
                                                                            List<String> actions) {
        List<String> policyVersions = new ArrayList<>();
        List<String> delegationEvidence = new ArrayList<>();
        for (String action : context.requiredActions()) {
            AgentToolServiceAuthorizationRemoteResult result = permissionAdminClient.evaluate(new AgentToolServiceAuthorizationRemoteRequest(
                    context.tenantId(),
                    properties.getServiceAccountActorId(),
                    properties.getServiceAccountRole(),
                    httpMethodFor(action),
                    context.targetEndpoint(),
                    context.resourceType(),
                    action,
                    properties.getServiceAccountCode(),
                    context.representedActorId(),
                    "SERVICE_ACCOUNT_ON_BEHALF_OF_ACTOR",
                    delegationReason(context, action),
                    null,
                    context.traceId()
            ));
            if (!Boolean.TRUE.equals(result.allowed())) {
                reasons.add("permission-admin 拒绝动作 " + action + "，原因：" + result.reason());
                actions.add("请在 permission-admin 中检查 SERVICE_ACCOUNT 角色对 " + context.resourceType()
                        + " / " + action + " 的路由策略、数据范围和审批要求。");
                return buildView(context, AgentToolServiceAuthorizationDecision.PERMISSION_ADMIN_REJECTED, false, reasons, actions);
            }
            reasons.add("permission-admin 允许动作 " + action + "，routeEffect=" + result.routeEffect()
                    + "，dataScopeLevel=" + result.dataScopeLevel() + "。");
            if (result.policyVersion() != null && !result.policyVersion().isBlank()) {
                reasons.add("permission-admin 策略版本：" + result.policyVersion() + "。");
                policyVersions.add(result.policyVersion().trim());
            }
            if (result.delegationEvidence() != null && !result.delegationEvidence().isBlank()) {
                reasons.add("permission-admin 已记录服务账号委托证据：" + result.delegationEvidence() + "。");
                delegationEvidence.add(result.delegationEvidence().trim());
            }
            if (Boolean.TRUE.equals(result.approvalRequired())) {
                actions.add("permission-admin 标记该动作仍需审批，真实执行器应进入审批/人工确认链路，而不是直接执行。");
            }
        }
        actions.add("真实执行前仍需在执行入口二次读取最新工具状态、权限策略和租户配额，避免 preview 后状态漂移。");
        return buildView(
                context,
                AgentToolServiceAuthorizationDecision.PERMISSION_ADMIN_ALLOWED,
                true,
                policyVersions.stream().distinct().toList(),
                delegationEvidence.stream().distinct().toList(),
                reasons,
                actions
        );
    }

    private AgentToolServiceAuthorizationPreviewView buildView(AuthorizationContext context,
                                                               AgentToolServiceAuthorizationDecision decision,
                                                               boolean allowed,
                                                               List<String> reasons,
                                                               List<String> actions) {
        return buildView(context, decision, allowed, List.of(), List.of(), reasons, actions);
    }

    private AgentToolServiceAuthorizationPreviewView buildView(AuthorizationContext context,
                                                               AgentToolServiceAuthorizationDecision decision,
                                                               boolean allowed,
                                                               List<String> policyVersions,
                                                               List<String> delegationEvidence,
                                                               List<String> reasons,
                                                               List<String> actions) {
        return new AgentToolServiceAuthorizationPreviewView(
                Boolean.TRUE.equals(properties.getEnabled()),
                mode().name(),
                decision.name(),
                allowed,
                Boolean.TRUE.equals(properties.getEnforceInPreview()),
                properties.getServiceAccountCode(),
                properties.getServiceAccountActorId(),
                properties.getServiceAccountRole(),
                context.representedActorId(),
                context.tenantId(),
                context.projectId(),
                context.workspaceId(),
                context.resourceType(),
                context.targetService(),
                context.targetEndpoint(),
                context.targetResourceId(),
                List.copyOf(context.requiredActions()),
                "SERVICE_ACCOUNT_ON_BEHALF_OF_ACTOR",
                delegationReason(context, context.requiredActions().isEmpty() ? null : context.requiredActions().get(0)),
                policyVersions == null ? List.of() : List.copyOf(policyVersions),
                delegationEvidence == null ? List.of() : List.copyOf(delegationEvidence),
                List.copyOf(reasons),
                List.copyOf(actions)
        );
    }

    /**
     * 生成传给 permission-admin 的委托原因。
     *
     * <p>Agent Runtime 调用权限中心时，不应该只说“我是 SERVICE_ACCOUNT”。生产审计更关心的是：
     * 这次机器身份为什么要代表某个用户继续推进工具动作、目标服务是谁、目标端点是什么、动作是什么。
     * 这里把这些低敏上下文拼成原因，既方便 permission-admin 审计，又避免把工具参数或 prompt 泄漏到权限日志。</p>
     */
    private String delegationReason(AuthorizationContext context, String action) {
        return "AGENT_RUNTIME_TOOL_PREVIEW"
                + ":tool=" + context.toolCode()
                + ":targetService=" + context.targetService()
                + ":targetEndpoint=" + context.targetEndpoint()
                + ":action=" + action;
    }

    private String httpMethodFor(String action) {
        String normalized = normalize(action);
        if ("VIEW".equals(normalized)) {
            return "GET";
        }
        if ("DELETE".equals(normalized)) {
            return "DELETE";
        }
        if ("UPDATE".equals(normalized) || "ENABLE".equals(normalized) || "DISABLE".equals(normalized)) {
            return "PUT";
        }
        return "POST";
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private AgentToolServiceAuthorizationMode mode() {
        return properties.getMode() == null ? AgentToolServiceAuthorizationMode.LOCAL_PREVIEW : properties.getMode();
    }

    private List<String> missingRequiredFields(AuthorizationContext context) {
        List<String> missingFields = new ArrayList<>(context.missingRequiredFields());
        if (properties.getServiceAccountActorId() == null) {
            missingFields.add("serviceAccountActorId");
        }
        if (properties.getServiceAccountCode() == null || properties.getServiceAccountCode().isBlank()) {
            missingFields.add("serviceAccountCode");
        }
        if (properties.getServiceAccountRole() == null || properties.getServiceAccountRole().isBlank()) {
            missingFields.add("serviceAccountRole");
        }
        return missingFields;
    }

    /**
     * 授权预览上下文。
     *
     * <p>把上下文收拢成内部 record，可以避免主流程在 audit/node/policy 三个对象之间反复取字段，
     * 也能把“缺什么字段算授权上下文不完整”集中在一个地方维护。</p>
     */
    private record AuthorizationContext(String toolCode,
                                        String executionMode,
                                        String targetService,
                                        String targetEndpoint,
                                        Long targetResourceId,
                                        Long tenantId,
                                        Long projectId,
                                        Long workspaceId,
                                        String representedActorId,
                                        String traceId,
                                        String resourceType,
                                        List<String> requiredActions) {

        static AuthorizationContext from(AgentToolExecutionAuditView audit,
                                         AgentToolPlanDagNodeView node,
                                         AgentRunToolExecutionPolicyItemView policy) {
            String toolCode = audit == null ? node.toolCode() : audit.toolCode();
            String executionMode = audit == null ? node.executionMode() : audit.executionMode();
            return new AuthorizationContext(
                    toolCode,
                    executionMode,
                    audit == null ? null : audit.targetService(),
                    audit == null ? null : audit.targetEndpoint(),
                    audit == null ? null : audit.targetResourceId(),
                    audit == null ? null : audit.tenantId(),
                    audit == null ? null : audit.projectId(),
                    audit == null ? null : audit.workspaceId(),
                    audit == null ? null : audit.actorId(),
                    audit == null ? null : audit.traceId(),
                    resourceTypeFor(audit == null ? null : audit.targetService(), toolCode),
                    requiredActionsFor(audit == null ? List.of() : audit.allowedActions(), executionMode, policy)
            );
        }

        List<String> missingRequiredFields() {
            List<String> missing = new ArrayList<>();
            if (tenantId == null) {
                missing.add("tenantId");
            }
            if (projectId == null) {
                missing.add("projectId");
            }
            if (representedActorId == null || representedActorId.isBlank()) {
                missing.add("representedActorId");
            }
            if (toolCode == null || toolCode.isBlank()) {
                missing.add("toolCode");
            }
            if (targetService == null || targetService.isBlank()) {
                missing.add("targetService");
            }
            if (targetEndpoint == null || targetEndpoint.isBlank()) {
                missing.add("targetEndpoint");
            }
            if (requiredActions.isEmpty()) {
                missing.add("requiredActions");
            }
            return missing;
        }

        private static String resourceTypeFor(String targetService, String toolCode) {
            String service = normalize(targetService);
            String tool = normalize(toolCode);
            if (service.contains("DATASOURCE") || tool.startsWith("DATASOURCE.")) {
                return "DATASOURCE";
            }
            if (service.contains("DATA-QUALITY") || tool.startsWith("QUALITY.")) {
                return "QUALITY_RULE";
            }
            if (service.contains("DATA-SYNC") || tool.startsWith("DATA-SYNC.")) {
                return "SYNC_TASK";
            }
            if (service.contains("TASK-MANAGEMENT") || tool.startsWith("TASK.")) {
                return "TASK";
            }
            return "AGENT_TOOL";
        }

        private static List<String> requiredActionsFor(List<String> allowedActions,
                                                       String executionMode,
                                                       AgentRunToolExecutionPolicyItemView policy) {
            List<String> normalizedActions = allowedActions == null ? List.of() : allowedActions.stream()
                    .filter(action -> action != null && !action.isBlank())
                    .map(AuthorizationContext::canonicalAction)
                    .distinct()
                    .toList();
            if (!normalizedActions.isEmpty()) {
                return normalizedActions;
            }
            if (policy != null && Boolean.TRUE.equals(policy.readOnly())) {
                return List.of("VIEW");
            }
            String mode = normalize(executionMode);
            if (AgentToolExecutionMode.DRAFT_ONLY.name().equals(mode)
                    || AgentToolExecutionMode.APPROVAL_REQUIRED.name().equals(mode)) {
                return List.of("CREATE");
            }
            return List.of("EXECUTE");
        }

        private static String canonicalAction(String action) {
            String normalized = normalize(action);
            if ("READ".equals(normalized)) {
                return "VIEW";
            }
            if ("WRITE".equals(normalized) || "RUN".equals(normalized)) {
                return "EXECUTE";
            }
            if ("GENERATE".equals(normalized)) {
                return "CREATE";
            }
            return normalized;
        }
    }
}
