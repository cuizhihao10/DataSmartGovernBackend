/**
 * @Author : Cui
 * @Date: 2026/05/31 18:16
 * @Description DataSmart Govern Backend - AgentToolPlanArgumentsPayloadView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 工具计划参数受控载荷视图。
 *
 * <p>该 DTO 服务于 `agent-tool-audit://.../plan-arguments` 引用解析场景。
 * 在 4.47-4.50 的 outbox/Kafka 链路中，跨服务 command 只携带 payloadReference、参数名和治理上下文，
 * 不携带真实参数值。task-management worker 真正准备执行时，才通过内部接口回到 agent-runtime 读取本视图。</p>
 *
 * <p>为什么要单独建这个 DTO，而不是直接复用 {@link AgentToolExecutionAuditView}：</p>
 * <p>1. 审计视图面向前端和审计查询，字段更宽；本视图只表达“执行侧解析参数引用”所需的稳定契约；</p>
 * <p>2. payloadReference、payloadKind、resolvedAt 是引用解析语义，原始审计记录中并不存在；</p>
 * <p>3. 后续如果引入脱敏策略、密钥引用解析、payload 版本或服务间签名，本 DTO 可以单独演进，避免污染通用审计查询；</p>
 * <p>4. record 构造器会复制集合和 Map，避免调用方拿到对象后修改内存快照，影响测试或后续缓存语义。</p>
 *
 * <p>安全提醒：</p>
 * <p>planArguments 可能包含 SQL、连接引用、字段名、过滤条件、文件路径或其他业务敏感参数。
 * 当前接口必须被视为内部控制面接口，生产环境应由 gateway、mTLS、服务账号令牌或内部网络策略保护，
 * 不能直接暴露给普通用户或浏览器。</p>
 */
public record AgentToolPlanArgumentsPayloadView(
        /**
         * 原始受控引用，格式固定为 `agent-tool-audit://{sessionId}/{runId}/{auditId}/plan-arguments`。
         * 执行侧会用它与 task.params 中保存的引用做一致性校验，防止命令和参数快照错配。
         */
        String payloadReference,

        /**
         * 载荷类型。当前只支持 `plan-arguments`，后续可扩展为 output-summary、artifact-manifest 等受控类型。
         */
        String payloadKind,

        /**
         * Agent 会话 ID，用于限定工作空间和用户上下文。
         */
        String sessionId,

        /**
         * Agent Run ID，用于限定本次编排尝试，避免 worker 读取历史 run 的参数。
         */
        String runId,

        /**
         * 工具审计 ID，是跨 outbox、Kafka、task-management Inbox 和 resolver 的核心关联键。
         */
        String auditId,

        /**
         * 工具编码，例如 `data-sync.execute`。执行侧必须复核该值，不能只信任 Kafka command。
         */
        String toolCode,

        /**
         * 目标业务服务名，例如 data-sync、datasource-management。
         */
        String targetService,

        /**
         * 目标业务端点。当前只作为治理元数据和未来适配器路由提示，不能被 worker 直接当成任意 URL 调用。
         */
        String targetEndpoint,

        /**
         * 租户、项目和工作空间边界。执行侧应使用这些字段做二次数据范围校验。
         */
        Long tenantId,
        Long projectId,
        Long workspaceId,

        /**
         * 原始触发者与链路追踪 ID，用于审计、下游 Header 透传和故障排查。
         */
        String actorId,
        String traceId,

        /**
         * 工具执行模式与当前审计状态。worker 可据此判断是否仍处于可执行窗口。
         */
        String executionMode,
        String state,

        /**
         * 参数名快照，只包含字段名，不包含字段值。用于 task-management 校验 command 摘要是否和审计快照一致。
         */
        List<String> argumentNames,

        /**
         * 敏感参数名快照。执行侧、日志侧和运维台应该用它避免打印对应字段值。
         */
        List<String> sensitiveArgumentNames,

        /**
         * 模型或编排器生成的计划参数。
         *
         * <p>这里仍然返回原始参数 Map，因为真正执行必须拿到参数值；安全边界靠内部接口、服务账号鉴权、
         * 字段名白名单、payload 大小限制和后续 permission-admin 策略共同保护。</p>
         */
        Map<String, Object> planArguments,

        /**
         * 治理提示，例如 sensitiveFields、riskReason、approvalPolicy 等。
         */
        Map<String, Object> governanceHints,

        /**
         * 参数校验结果，例如 missingFields、invalidFields、warningFields。
         */
        Map<String, Object> parameterValidation,

        /**
         * 本次解析生成时间。它不是审计创建时间，而是 resolver 读取快照的时间点。
         */
        LocalDateTime resolvedAt) {

    public AgentToolPlanArgumentsPayloadView {
        argumentNames = argumentNames == null ? List.of() : List.copyOf(argumentNames);
        sensitiveArgumentNames = sensitiveArgumentNames == null ? List.of() : List.copyOf(sensitiveArgumentNames);
        planArguments = planArguments == null ? Map.of() : new LinkedHashMap<>(planArguments);
        governanceHints = governanceHints == null ? Map.of() : new LinkedHashMap<>(governanceHints);
        parameterValidation = parameterValidation == null ? Map.of() : new LinkedHashMap<>(parameterValidation);
    }
}
