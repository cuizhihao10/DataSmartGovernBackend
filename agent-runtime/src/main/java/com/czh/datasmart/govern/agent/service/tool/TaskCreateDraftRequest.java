/**
 * @Author : Cui
 * @Date: 2026/05/24 23:10
 * @Description DataSmart Govern Backend - TaskCreateDraftRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.tool;

import java.util.Map;

/**
 * `task.create.draft` 工具的内部请求模型。
 *
 * <p>它不是 task-management 的 `CreateTaskRequest`，也不会被直接发送到 `/tasks`。
 * 这里特意建立 agent-runtime 自己的草稿模型，是为了把“Agent 生成任务建议”和“任务中心真实创建任务”
 * 分成两个阶段：</p>
 *
 * <p>1. 第一阶段：Agent 只整理任务意图、执行目标、来源工具输出和推荐参数，形成可审阅草稿；</p>
 * <p>2. 第二阶段：用户或审批流确认后，才允许调用 task-management 创建真实 PENDING 任务；</p>
 * <p>3. 这样可以避免模型一次规划就把任务塞进队列，造成误调度、越权执行或生产资源消耗。</p>
 *
 * @param tenantId 当前租户 ID，来自 Agent Session，是任务草稿的租户隔离边界。
 * @param projectId 当前项目 ID，来自 Agent Session，是后续任务可见性和队列配额的核心维度。
 * @param workspaceId 当前工作空间 ID，可为空；未来可用于多人协作空间、实验空间或私有沙箱隔离。
 * @param taskType 建议任务类型，例如 DATA_QUALITY_SCAN、DATA_SYNC、MANUAL_REVIEW。
 * @param objective 用户希望任务达成的业务目标，会进入任务描述和审计说明。
 * @param priority 建议优先级。草稿阶段只做归一化，不触发真实调度优先级排序。
 * @param maxRetryCount 建议最大重试次数。真实创建时仍应由 task-management 再次校验。
 * @param maxDeferCount 建议最大连续退避次数，用于执行器背压保护。
 * @param sourceSuggestion 来自前序工具的建议输出，例如 quality.rule.suggest 的规则草案结果。
 */
public record TaskCreateDraftRequest(Long tenantId,
                                     Long projectId,
                                     Long workspaceId,
                                     String taskType,
                                     String objective,
                                     String priority,
                                     Integer maxRetryCount,
                                     Integer maxDeferCount,
                                     Map<String, Object> sourceSuggestion) {
}
