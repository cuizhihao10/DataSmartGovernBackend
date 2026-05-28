/**
 * @Author : Cui
 * @Date: 2026/05/24 23:11
 * @Description DataSmart Govern Backend - TaskCreateDraftToolAdapter.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.tool;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * `task.create.draft` 工具适配器。
 *
 * <p>该工具是 Agent 工作流从“建议”走向“可执行计划”的关键一步。
 * 但它必须被设计成无副作用草稿工具：当前 task-management 的 `/tasks` 一旦调用就会创建 PENDING 任务，
 * PENDING 任务对执行器来说是可认领、可调度、会消耗资源的真实任务。因此本适配器不会发起 HTTP 写调用，
 * 只返回结构化草稿，等待用户审批或后续正式草稿表能力承接。</p>
 *
 * <p>这也是商业化 Agent 平台中很重要的边界设计：
 * 模型可以辅助规划，但不能绕过审批直接制造生产任务。
 * 真正落库前还需要 permission-admin 的动作级权限、项目范围校验、task-management 的幂等键、
 * data-quality 的规则状态检查，以及执行器容量/窗口策略。</p>
 */
@Component
public class TaskCreateDraftToolAdapter implements AgentToolAdapter {

    public static final String TOOL_CODE = "task.create.draft";

    private final TaskCreateDraftRequestFactory requestFactory;
    private final TaskCreateDraftPayloadBuilder payloadBuilder;

    public TaskCreateDraftToolAdapter(TaskCreateDraftRequestFactory requestFactory,
                                      TaskCreateDraftPayloadBuilder payloadBuilder) {
        this.requestFactory = requestFactory;
        this.payloadBuilder = payloadBuilder;
    }

    @Override
    public boolean supports(String toolCode) {
        return TOOL_CODE.equals(toolCode);
    }

    /**
     * 执行草稿生成。
     *
     * <p>这里没有 try-catch 包住所有异常，是因为参数缺失、状态冲突等平台异常应由
     * `AgentToolExecutionService` 统一转成工具失败审计。适配器本身只负责成功路径：
     * 构建受控请求，再构建结构化草稿输出。</p>
     */
    @Override
    public AgentToolExecutionOutcome execute(AgentToolExecutionContext context) {
        TaskCreateDraftRequest request = requestFactory.build(context);
        Map<String, Object> output = payloadBuilder.build(request);
        return AgentToolExecutionOutcome.succeeded(
                "任务草稿已生成。该工具未调用 task-management，真实创建任务前仍需人工审批。",
                output
        );
    }
}
