/**
 * @Author : Cui
 * @Date: 2026/05/31 23:20
 * @Description DataSmart Govern Backend - AgentSyncTaskExecuteRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import lombok.Data;

/**
 * Agent 触发数据同步任务的内部请求契约。
 *
 * <p>这个 DTO 只服务于 `/internal/data-sync/agent/tasks/execute` 内部入口，不直接暴露给普通前端用户。
 * 它把 Agent Runtime 侧的一次工具调用、task-management 侧的一条异步任务、data-sync 侧的一次同步任务创建与入队
 * 串在同一个业务动作里。</p>
 *
 * <p>为什么不让 task-management 直接调用公开的 `POST /sync-tasks` 再调用 `POST /sync-tasks/{id}/run`？
 * 因为 worker 是会重试的：如果“创建成功但响应超时”，下一次重试会再次创建同步任务，造成重复任务和重复数据搬运。
 * 所以这里要求调用方携带 commandId/idempotencyKey，由 data-sync 在服务端把“创建 + 入队”做成幂等原子业务语义。</p>
 */
@Data
public class AgentSyncTaskExecuteRequest {

    /** Agent 异步命令 ID，通常来自 agent-runtime command outbox，用于跨模块排障和幂等摘要。 */
    private String commandId;

    /** 调用方生成的幂等键，同一条 Agent 工具执行重试时必须保持不变。 */
    private String idempotencyKey;

    /** Agent 工具审计 ID，用于把 data-sync 任务反查到具体工具调用。 */
    private String auditId;

    /** Agent 会话 ID，用于构造幂等作用域并关联会话级审计。 */
    private String sessionId;

    /** Agent Run ID，用于区分同一会话内不同轮次或不同执行计划。 */
    private String runId;

    /** 工具编码。当前内部入口只接受 data-sync.execute，避免被复用成任意执行入口。 */
    private String toolCode;

    /** 租户 ID，是 data-sync 幂等隔离、模板读取和任务写入的基础边界。 */
    private Long tenantId;

    /** 项目 ID，默认继承模板；显式传入时 data-sync 会校验与模板归属一致。 */
    private Long projectId;

    /** 工作空间 ID，默认继承模板；用于未来工作空间级任务看板和配额。 */
    private Long workspaceId;

    /** Agent 代表的真实业务操作者。当前 data-sync actorId 是 Long，非数字值会由服务层降级为空。 */
    private String actorId;

    /** 链路追踪 ID，贯穿 Agent、task-management、data-sync 和后续执行器日志。 */
    private String traceId;

    /** 同步模板 ID。字段名兼容 Agent 规划里常见的 templateId。 */
    private Long templateId;

    /** 同步模板 ID 的业务化别名，便于模型或工具 schema 使用 syncTemplateId 表达语义。 */
    private Long syncTemplateId;

    /** 同步任务名称；为空时服务端会按 commandId 生成稳定名称，降低重试重复创建风险。 */
    private String name;

    /** 同步任务说明；建议记录“由 Agent 工具触发”的上下文，便于运营台识别来源。 */
    private String description;

    /** 同步任务优先级，默认 MEDIUM；未来可接入租户配额、SLA 和队列调度策略。 */
    private String priority;

    /** 运行模式，默认 TEMPLATE；后续可扩展为 BACKFILL、REPLAY、CDC_BOOTSTRAP 等模式。 */
    private String runMode;

    /** 负责人 ID；为空时 data-sync 会回退到 actorId 或服务账号默认语义。 */
    private Long ownerId;
}
