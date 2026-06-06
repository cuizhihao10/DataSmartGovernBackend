/**
 * @Author : Cui
 * @Date: 2026/06/02 18:38
 * @Description DataSmartGovernBackend - AgentToolBudgetPolicyServiceImplTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service.impl;

import com.czh.datasmart.govern.permission.controller.dto.AgentToolBudgetPolicyEvaluateRequest;
import com.czh.datasmart.govern.permission.controller.dto.AgentToolBudgetPolicyView;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Agent 工具预算策略服务测试。
 *
 * <p>这些测试不启动 Spring 容器，也不依赖 MySQL。
 * 原因是本阶段要保护的是策略算法契约：不同角色、套餐、风险和 backlog 输入，
 * 是否能生成 Python Runtime 可消费的 `toolCallBudget`。</p>
 */
class AgentToolBudgetPolicyServiceImplTest {

    private final AgentToolBudgetPolicyServiceImpl service = new AgentToolBudgetPolicyServiceImpl();

    /**
     * 普通用户在默认套餐下应使用保守预算。
     *
     * <p>这条用例保护商业化安全边界：普通用户不能因为模型提出更多工具，就自动获得更多后台执行预算。</p>
     */
    @Test
    void ordinaryUserShouldUseConservativeBudget() {
        AgentToolBudgetPolicyEvaluateRequest request = request("ORDINARY_USER");

        AgentToolBudgetPolicyView view = service.evaluate(request);

        assertThat(view.getAllowed()).isTrue();
        assertThat(view.getPolicySource()).isEqualTo("IN_MEMORY_RULE");
        assertThat(view.getToolCallBudget())
                .containsEntry("maxProposedToolCalls", 5)
                .containsEntry("maxAutoExecutableToolCalls", 2)
                .containsEntry("maxHighRiskToolCalls", 0);
        assertThat(view.getNotes()).anyMatch(note -> note.contains("普通用户"));
    }

    /**
     * 平台管理员在平台内部套餐下可以获得更宽预算，但仍应保持有上限。
     */
    @Test
    void platformAdministratorWithInternalPlanShouldHaveWiderBudget() {
        AgentToolBudgetPolicyEvaluateRequest request = request("PLATFORM_ADMINISTRATOR");
        request.setTenantPlanCode("PLATFORM_INTERNAL");
        request.setWorkspaceRiskLevel("LOW");

        AgentToolBudgetPolicyView view = service.evaluate(request);

        assertThat(view.getToolCallBudget())
                .containsEntry("maxProposedToolCalls", 16)
                .containsEntry("maxAutoExecutableToolCalls", 7)
                .containsEntry("maxHighRiskToolCalls", 3);
        assertThat(view.getRecommendedActions()).contains("可以把 toolCallBudget 注入 Python Runtime 请求变量继续执行。");
    }

    /**
     * 高风险 workspace 必须收紧自动推进和高风险工具预算。
     */
    @Test
    void highRiskWorkspaceShouldTightenAutoAndHighRiskBudget() {
        AgentToolBudgetPolicyEvaluateRequest request = request("PROJECT_OWNER");
        request.setWorkspaceRiskLevel("HIGH");

        AgentToolBudgetPolicyView view = service.evaluate(request);

        assertThat(view.getToolCallBudget())
                .containsEntry("maxAutoExecutableToolCalls", 2)
                .containsEntry("maxHighRiskToolCalls", 0);
        assertThat(view.getNotes()).anyMatch(note -> note.contains("高风险 workspace"));
    }

    /**
     * worker backlog 极高时，应把预算收缩到极小批次。
     *
     * <p>这条用例保护性能和可靠性需求：Agent 不能在下游 worker 已经积压时继续放大后台压力。</p>
     */
    @Test
    void criticalBacklogShouldShrinkBudgetToTinyBatch() {
        AgentToolBudgetPolicyEvaluateRequest request = request("TENANT_ADMINISTRATOR");
        request.setTenantPlanCode("ENTERPRISE");
        request.setWorkerBacklogLevel("CRITICAL");

        AgentToolBudgetPolicyView view = service.evaluate(request);

        assertThat(view.getToolCallBudget())
                .containsEntry("maxProposedToolCalls", 3)
                .containsEntry("maxAutoExecutableToolCalls", 1)
                .containsEntry("maxHighRiskToolCalls", 0);
        assertThat(view.getRecommendedActions()).anyMatch(action -> action.contains("拆成更小批次"));
        assertThat(view.getRecommendedActions()).anyMatch(action -> action.contains("backlog"));
    }

    /**
     * CRITICAL 工具风险应默认要求强收紧。
     */
    @Test
    void criticalToolRiskShouldRequireManualLikeBudget() {
        AgentToolBudgetPolicyEvaluateRequest request = request("SERVICE_ACCOUNT");
        request.setRequestedToolRiskLevel("CRITICAL");

        AgentToolBudgetPolicyView view = service.evaluate(request);

        assertThat(view.getToolCallBudget())
                .containsEntry("maxAutoExecutableToolCalls", 1)
                .containsEntry("maxHighRiskToolCalls", 0);
        assertThat(view.getNotes()).anyMatch(note -> note.contains("CRITICAL 工具风险"));
    }

    /**
     * permission-admin 应同时输出 Python Runtime 5.38 可消费的标准 readiness policy。
     *
     * <p>这个测试保护 5.39 的关键产品合同：旧的 `toolCallBudget` 继续存在，但新链路应优先使用
     * `toolExecutionReadinessPolicy`。该策略只包含低敏控制面元数据，不能携带 prompt、SQL、工具参数值、
     * 内部 endpoint 或凭证。</p>
     */
    @Test
    void shouldExposeStandardToolExecutionReadinessPolicyForPythonRuntime() {
        AgentToolBudgetPolicyEvaluateRequest request = request("AUDITOR");
        request.setTenantPlanCode("FREE");
        request.setWorkspaceRiskLevel("HIGH");
        request.setWorkerBacklogLevel("CRITICAL");

        AgentToolBudgetPolicyView view = service.evaluate(request);
        String serializedLike = view.getToolExecutionReadinessPolicy().toString();

        assertThat(view.getToolExecutionReadinessPolicy()).isNotNull();
        assertThat(view.getToolExecutionReadinessPolicy().getSource()).isEqualTo("permission-admin");
        assertThat(view.getToolExecutionReadinessPolicy().getPolicyVersion()).contains("AUDITOR");
        assertThat(view.getToolExecutionReadinessPolicy().getActorRole()).isEqualTo("AUDITOR");
        assertThat(view.getToolExecutionReadinessPolicy().getTenantPlanCode()).isEqualTo("FREE");
        assertThat(view.getToolExecutionReadinessPolicy().getWorkspaceRiskLevel()).isEqualTo("HIGH");
        assertThat(view.getToolExecutionReadinessPolicy().getWorkerBacklogLevel()).isEqualTo("CRITICAL");
        assertThat(view.getToolExecutionReadinessPolicy().getMaxAutoSyncTools()).isEqualTo(1);
        assertThat(view.getToolExecutionReadinessPolicy().getMaxAsyncTools()).isZero();
        assertThat(view.getToolExecutionReadinessPolicy().getHighRiskRequiresApproval()).isTrue();
        assertThat(view.getToolExecutionReadinessPolicy().getCriticalRiskBlocked()).isTrue();
        assertThat(view.getToolExecutionReadinessPolicy().getAllowDraftWithoutAllParameters()).isFalse();
        assertThat(view.getToolExecutionReadinessPolicy().getInfluenceCodes())
                .contains("READ_ONLY_ROLE_LIMITS_AUTO_EXECUTION",
                        "TENANT_PLAN_LIMITS_TOOL_BUDGET",
                        "WORKSPACE_RISK_REQUIRES_APPROVAL",
                        "WORKER_BACKLOG_BLOCKS_TOOL_BUDGET");
        assertThat(serializedLike)
                .doesNotContain("prompt")
                .doesNotContain("sql")
                .doesNotContain("arguments")
                .doesNotContain("internalEndpoint")
                .doesNotContain("secret");
    }

    private AgentToolBudgetPolicyEvaluateRequest request(String actorRole) {
        AgentToolBudgetPolicyEvaluateRequest request = new AgentToolBudgetPolicyEvaluateRequest();
        request.setTenantId(10L);
        request.setProjectId("project-a");
        request.setWorkspaceKey("tenant:10:project:project-a");
        request.setActorRole(actorRole);
        request.setTenantPlanCode("STANDARD");
        request.setWorkspaceRiskLevel("NORMAL");
        request.setWorkerBacklogLevel("NORMAL");
        request.setRequestedToolRiskLevel("LOW");
        return request;
    }
}
