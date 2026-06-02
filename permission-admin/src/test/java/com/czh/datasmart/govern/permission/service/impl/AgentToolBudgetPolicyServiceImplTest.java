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
