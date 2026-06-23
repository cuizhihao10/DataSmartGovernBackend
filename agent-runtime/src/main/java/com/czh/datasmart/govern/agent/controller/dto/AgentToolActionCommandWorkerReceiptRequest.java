/**
 * @Author : Cui
 * @Date: 2026/06/23 00:00
 * @Description DataSmart Govern Backend - AgentToolActionCommandWorkerReceiptRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * 受控命令 worker 执行回执的内部请求合同。
 *
 * <p>这个 DTO 服务于 task-management / command-worker -> agent-runtime 的服务间回写场景，
 * 不是面向普通用户开放的浏览器接口。它表达的是“worker 已经看到某条 command outbox 指令，
 * 并完成了执行前复核或受控执行后的结果回执”。因此它和 dry-run receipt 有一个非常关键的差异：
 * dry-run receipt 永远不能声明真实副作用已经发生，而 command-worker receipt 可以在满足安全预检、
 * HITL、预算、沙箱和输出治理规则后声明 sideEffectExecuted=true。</p>
 *
 * <p>字段设计刻意只保留低敏事实。这里绝不接收 commandLine、workingDirectory、真实路径、
 * 环境变量、stdout/stderr、工具实参、payload body、SQL、prompt、模型输出、凭据或内部 endpoint。
 * 真实执行产物必须先落到受权限保护的对象存储/制品系统，再用 artifactReference 这种低敏引用进入回执。
 * 这样 timeline、审计、恢复事实包和运营指标都能知道“执行发生了什么类型的结果”，但不会拿到正文数据。</p>
 *
 * @param commandId command outbox 的稳定指令 ID，用于串联 proposal、precheck、outbox、worker、receipt。
 * @param taskId task-management 任务主键；用于从 Agent timeline 反查任务中心记录。
 * @param taskRunId worker 认领本次任务生成的运行 ID；同一 task 多次重试时用于区分尝试。
 * @param executorId worker 实例或逻辑执行器 ID；只允许低敏标识，不允许携带主机名、IP 或路径。
 * @param tenantId 租户边界；进入 runtime event 后用于控制台查询和审计范围收口。
 * @param projectId 项目边界；后续恢复事实包、权限过滤和项目负责人视图都会使用该字段。
 * @param actorId 触发命令的用户、Agent 或服务账号 ID；为空代表只能识别到服务角色。
 * @param taskStatus worker 写回时 task-management 看到的任务状态摘要，例如 RUNNING、SUCCEEDED、FAILED。
 * @param outcome worker 回执结果，例如 WORKER_PRECHECK_PASSED、EXECUTION_SUCCEEDED、EXECUTION_FAILED。
 * @param preCheckPassed true 表示 worker 侧服务端复核通过；false 表示执行前被明确阻断。
 * @param sideEffectStarted true 表示 worker 已经进入可能产生副作用的受控执行区；用于区分“预检通过但尚未执行”。
 * @param sideEffectExecuted true 表示副作用已确认发生；只有安全决策允许受控执行时才能为 true。
 * @param commandSafetyDecision Java 安全预检或 worker 复核后的低敏决策，例如 ALLOW_CONTROLLED_EXECUTION。
 * @param commandSafetyPolicyVersion 命令安全策略版本；用于审计“当时用哪一版策略放行/阻断”。
 * @param commandSafetyIssueCodes 安全预检 issueCode 白名单摘要；不允许出现命令行、路径值或参数值。
 * @param normalizedTimeoutSeconds worker 采用的超时时间预算；只记录裁剪后的数值，不记录原始命令配置。
 * @param normalizedOutputByteLimitBytes worker 采用的输出字节预算；用于确认 stdout/stderr 已被治理。
 * @param artifactReferenceType 产物引用类型，例如 MINIO_OBJECT、AGENT_ARTIFACT、COMMAND_OUTPUT_SUMMARY。
 * @param artifactReference 低敏产物引用；必须是受控 scheme，不允许 http/https URL、真实路径或对象正文。
 * @param artifactAvailable true 表示产物元数据已经登记；不代表当前调用方可以读取产物正文。
 * @param errorCode 低敏错误码；用于前端、审计和运维聚合，不保存人类长文本错误堆栈。
 * @param auditId 低敏审计引用；用于串联 permission-admin 或 task-management 的审计记录。
 * @param toolCode 工具编码，例如 command.run-program；只作为目录标识，不包含工具参数。
 * @param targetService 目标服务逻辑名；不能是内部 URL，只能是低敏服务编码。
 * @param workerReceiptMode 回执模式，例如 PRECHECK_ONLY、EXECUTION_RESULT、COMPENSATION_REQUIRED。
 * @param message 面向运维的短摘要；agent-runtime 会做长度限制和敏感片段过滤。
 * @param recommendedActions 面向下一步处理的低敏建议，例如“查看审批事实”“等待 worker 容量恢复”。
 * @param idempotencyKey worker 生成的幂等键；用于避免重试回调把同一执行事实重复写入 timeline。
 */
public record AgentToolActionCommandWorkerReceiptRequest(
        String commandId,
        Long taskId,
        Long taskRunId,
        String executorId,
        Long tenantId,
        Long projectId,
        Long actorId,
        String taskStatus,
        String outcome,
        Boolean preCheckPassed,
        Boolean sideEffectStarted,
        Boolean sideEffectExecuted,
        String commandSafetyDecision,
        String commandSafetyPolicyVersion,
        List<String> commandSafetyIssueCodes,
        Integer normalizedTimeoutSeconds,
        Integer normalizedOutputByteLimitBytes,
        String artifactReferenceType,
        String artifactReference,
        Boolean artifactAvailable,
        String errorCode,
        String auditId,
        String toolCode,
        String targetService,
        String workerReceiptMode,
        String message,
        List<String> recommendedActions,
        String idempotencyKey
) {
}
