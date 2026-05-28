/**
 * @Author : Cui
 * @Date: 2026/05/24 23:58
 * @Description DataSmart Govern Backend - TaskDraftPersistRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.tool;

/**
 * `task.draft.persist` 工具的内部请求模型。
 *
 * <p>该 record 对齐 task-management 的 `CreateTaskDraftRequest`，但不直接依赖 task-management 模块的 DTO。
 * 原因是 agent-runtime 和 task-management 是两个独立微服务，跨服务复用 Controller DTO 会让模块之间产生编译期耦合，
 * 后续任意一侧改字段、改校验、改包名，都可能破坏另一个服务。</p>
 *
 * <p>字段语义说明：</p>
 * <p>1. name/description/type 是任务草稿的核心业务表达，用于审批页、草稿列表和未来转换真实任务；</p>
 * <p>2. tenantId/projectId 保证草稿写入后仍处在当前 Agent 会话的租户和项目边界内；</p>
 * <p>3. ownerId 当前尽量从会话 actorId 推导，无法推导时允许为空，由 task-management 的上下文规则兜底；</p>
 * <p>4. params 必须是 JSON 字符串，因为 task-management 当前表结构按字符串保存任务参数；</p>
 * <p>5. sourceType/sourceRef 记录草稿来源，便于审计追溯“这条草稿由哪次 Agent 工具执行生成”。</p>
 */
public record TaskDraftPersistRequest(String name,
                                      String description,
                                      String type,
                                      Long tenantId,
                                      Long ownerId,
                                      Long projectId,
                                      String params,
                                      String priority,
                                      Integer maxRetryCount,
                                      Integer maxDeferCount,
                                      String sourceType,
                                      String sourceRef) {
}
