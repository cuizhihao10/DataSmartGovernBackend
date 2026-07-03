/**
 * @Author : Cui
 * @Date: 2026/05/31 14:22
 * @Description DataSmart Govern Backend - AgentRunAsyncTaskCommandPlanningService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.execution;

import com.czh.datasmart.govern.agent.config.AgentRuntimeProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentAsyncTaskCommandPlanItemView;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunAsyncTaskCommandPlanView;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolExecutionPolicyItemView;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolExecutionPolicyView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionAuditView;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionMode;
import com.czh.datasmart.govern.agent.service.AgentToolExecutionAuditService;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Run 级 ASYNC_TASK 异步命令草案规划服务。
 *
 * <p>该服务是同步自动执行之后的下一阶段地基，但它刻意保持“只读规划”：
 * 不向 Kafka 发送消息、不创建 task-management 任务、不推进 Agent 工具审计状态。
 * 它只把已经处于 ASYNC_TASK 模式的工具审计记录转换为稳定、可解释、可审计的 command envelope 草案。</p>
 *
 * <p>为什么先做草案而不是直接接 Kafka：
 * 1. 异步消息通常是至少一次投递，必须先有稳定幂等键和消费者去重语义；
 * 2. task-management 需要知道租户、项目、工作空间、actor、traceId 和原始 auditId，才能做权限和回放；
 * 3. 前端和 Python Runtime 需要先看到哪些异步工具可下发、哪些被阻断，不能把失败藏在消息系统里；
 * 4. 后续 dispatcher、outbox、Kafka producer、死信和重放接口都可以围绕同一 command plan 演进。</p>
 *
 * <p>当前安全策略：
 * - 只处理 executionMode=ASYNC_TASK 的工具；
 * - 只有 policyDecision=WAITING_ASYNC_EXECUTOR 才能进入下发候选；
 * - 缺参数、等待审批、Run 终态等问题会由已有 policy 服务先阻断；
 * - 默认要求工具 idempotent=true，避免至少一次投递造成重复业务副作用。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentRunAsyncTaskCommandPlanningService {

    private static final String COMMAND_TYPE = "AGENT_TOOL_ASYNC_TASK_REQUESTED";
    private static final String DISPATCH_CHANNEL = "KAFKA_COMMAND";
    private static final String MCP_COMMAND_TYPE = "AGENT_MCP_TOOL_CALL_REQUESTED";
    private static final String MCP_COMMAND_TOPIC = "datasmart.agent.mcp.commands";
    private static final String MCP_CONSUMER_SERVICE = "python-ai-runtime-mcp-client";

    private final AgentRuntimeProperties properties;
    private final AgentRunToolExecutionPolicyService policyService;
    private final AgentToolExecutionAuditService auditService;

    /**
     * 为一次 Agent Run 生成异步工具命令草案。
     *
     * @param sessionId Agent 会话 ID，用于隔离工作空间。
     * @param runId Agent Run ID，用于隔离本次编排尝试。
     * @return 只读异步命令规划视图。
     */
    public AgentRunAsyncTaskCommandPlanView planRunAsyncTaskCommands(String sessionId, String runId) {
        ensurePlanningEnabled();
        AgentRunToolExecutionPolicyView policy = policyService.inspectRunPolicy(sessionId, runId);
        Map<String, AgentRunToolExecutionPolicyItemView> policyByAuditId = indexPolicies(policy);
        List<AgentToolExecutionAuditView> audits = auditService.listByRun(sessionId, runId);
        List<AgentAsyncTaskCommandPlanItemView> items = audits.stream()
                .filter(this::isAsyncTask)
                .map(audit -> toCommandPlan(audit, policyByAuditId.get(audit.auditId())))
                .toList();
        int dispatchableCount = (int) items.stream().filter(AgentAsyncTaskCommandPlanItemView::dispatchable).count();
        int blockedCount = items.size() - dispatchableCount;
        int ignoredCount = audits.size() - items.size();
        return new AgentRunAsyncTaskCommandPlanView(
                sessionId,
                runId,
                items.size(),
                dispatchableCount,
                blockedCount,
                ignoredCount,
                dispatchableCount > 0,
                buildSummaryReasons(items.size(), dispatchableCount, blockedCount, ignoredCount),
                buildRecommendedActions(items.size(), dispatchableCount, blockedCount),
                items
        );
    }

    /**
     * 把单条异步工具审计转换为 command envelope 草案。
     *
     * <p>草案里只暴露参数名，不暴露参数值。未来真正 dispatcher 应按 auditId 在服务内部重新读取参数快照，
     * 再经过 permission-admin、数据范围、敏感字段、密钥引用和下游 schema 校验后构造 Kafka payload。</p>
     */
    private AgentAsyncTaskCommandPlanItemView toCommandPlan(AgentToolExecutionAuditView audit,
                                                            AgentRunToolExecutionPolicyItemView policy) {
        List<String> reasons = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        boolean dispatchable = true;

        if (policy == null) {
            dispatchable = false;
            reasons.add("未找到对应的 Run 级执行策略项，不能在缺少统一策略解释时生成可下发命令。");
            actions.add("重新查询 execution-policy，并检查审计记录与 policy 列表是否一致。");
        } else if (!AgentRunToolExecutionDecision.WAITING_ASYNC_EXECUTOR.name().equals(policy.decision())) {
            dispatchable = false;
            reasons.add("当前 policyDecision=" + policy.decision() + "，尚未进入 WAITING_ASYNC_EXECUTOR。");
            actions.addAll(policy.recommendedActions());
        }

        if (Boolean.TRUE.equals(properties.getRequireIdempotentAsyncTaskCommands())
                && !Boolean.TRUE.equals(audit.idempotent())) {
            dispatchable = false;
            reasons.add("异步工具未声明幂等。Kafka 至少一次投递可能造成重复任务或重复副作用，当前严格模式阻断自动下发。");
            actions.add("为工具补充幂等语义，或先在 task-management 落地 command 去重表和人工复核流程。");
        }

        if (dispatchable) {
            reasons.add("工具已进入 WAITING_ASYNC_EXECUTOR，且满足当前异步命令草案安全基线。");
            actions.add("后续 dispatcher 可按 commandId/idempotencyKey 写入 outbox，再投递 Kafka command。");
        }

        return new AgentAsyncTaskCommandPlanItemView(
                audit.auditId(),
                audit.toolCode(),
                audit.state(),
                policy == null ? AgentRunToolExecutionDecision.BLOCKED_BY_POLICY.name() : policy.decision(),
                dispatchable,
                stableCommandId(audit),
                stableIdempotencyKey(audit),
                isMcpTool(audit.toolCode()) ? MCP_COMMAND_TYPE : COMMAND_TYPE,
                DISPATCH_CHANNEL,
                isMcpTool(audit.toolCode())
                        ? MCP_COMMAND_TOPIC
                        : normalizeText(properties.getAsyncTaskCommandTopic(), "datasmart.agent.tool.async.commands"),
                isMcpTool(audit.toolCode())
                        ? MCP_CONSUMER_SERVICE
                        : normalizeText(properties.getAsyncTaskCommandConsumerService(), "task-management"),
                audit.targetService(),
                audit.targetEndpoint(),
                audit.tenantId(),
                audit.projectId(),
                audit.workspaceId(),
                audit.actorId(),
                audit.traceId(),
                Boolean.TRUE.equals(audit.idempotent()),
                sortedKeys(audit.planArguments()),
                sensitiveArgumentNames(audit.governanceHints()),
                List.copyOf(reasons),
                actions.isEmpty() ? List.of("等待策略条件满足后重新生成命令草案。") : List.copyOf(actions)
        );
    }

    private Map<String, AgentRunToolExecutionPolicyItemView> indexPolicies(AgentRunToolExecutionPolicyView policy) {
        Map<String, AgentRunToolExecutionPolicyItemView> indexed = new LinkedHashMap<>();
        for (AgentRunToolExecutionPolicyItemView item : policy.items()) {
            indexed.put(item.auditId(), item);
        }
        return indexed;
    }

    private boolean isAsyncTask(AgentToolExecutionAuditView audit) {
        return AgentToolExecutionMode.ASYNC_TASK.name().equals(normalizeEnumName(audit.executionMode()));
    }

    /**
     * 判断工具是否属于 MCP 出站命名空间。
     *
     * <p>统一使用 {@code mcp.<serverId>.<toolName>}，让规划、outbox、dispatcher 与 Python Runtime
     * 在不传递外部 endpoint 的情况下识别同一个工具。这里只做协议分类；server 与 tool 是否真实注册，
     * 仍由 Python MCP registry 和执行时 admission allowlist 最终校验。</p>
     */
    private boolean isMcpTool(String toolCode) {
        return toolCode != null && toolCode.trim().toLowerCase(Locale.ROOT).startsWith("mcp.");
    }

    private List<String> buildSummaryReasons(int totalAsyncTools,
                                             int dispatchableCount,
                                             int blockedCount,
                                             int ignoredCount) {
        List<String> reasons = new ArrayList<>();
        if (totalAsyncTools == 0) {
            reasons.add("当前 Run 没有 ASYNC_TASK 工具，不需要生成异步命令草案。");
        }
        if (dispatchableCount > 0) {
            reasons.add("存在可进入后续 dispatcher 的异步命令草案；当前接口仍然不会产生 Kafka 投递副作用。");
        }
        if (blockedCount > 0) {
            reasons.add("存在被审批、参数、状态或幂等策略阻断的异步工具，需要先处理阻断原因。");
        }
        if (ignoredCount > 0) {
            reasons.add("非 ASYNC_TASK 工具已忽略，它们应继续走同步执行、草稿审查或审批流程。");
        }
        return reasons;
    }

    private List<String> buildRecommendedActions(int totalAsyncTools,
                                                 int dispatchableCount,
                                                 int blockedCount) {
        List<String> actions = new ArrayList<>();
        if (totalAsyncTools == 0) {
            actions.add("继续按 execution-policy 处理同步、审批或草稿类工具。");
        }
        if (dispatchableCount > 0) {
            actions.add("下一阶段实现 outbox + Kafka dispatcher，并由 task-management 消费 command 创建可恢复任务。");
        }
        if (blockedCount > 0) {
            actions.add("优先处理审批、参数补全和非幂等工具治理，避免消息重复投递造成副作用。");
        }
        return actions;
    }

    /**
     * 生成稳定命令 ID。
     *
     * <p>同一个 sessionId/runId/auditId 多次查询必须得到相同 commandId，后续 dispatcher 才能安全重试，
     * task-management 消费者也才能做重复消息去重。这里使用 SHA-256 截断摘要，不依赖随机 UUID。</p>
     */
    private String stableCommandId(AgentToolExecutionAuditView audit) {
        return "aatc_" + sha256(audit.sessionId() + ":" + audit.runId() + ":" + audit.auditId()).substring(0, 24);
    }

    private String stableIdempotencyKey(AgentToolExecutionAuditView audit) {
        return "agent-tool-async:" + audit.sessionId() + ":" + audit.runId() + ":" + audit.auditId();
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256，无法生成稳定异步命令 ID", exception);
        }
    }

    private List<String> sortedKeys(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return List.of();
        }
        return arguments.keySet().stream().map(String::valueOf).sorted().toList();
    }

    /**
     * 从治理提示读取敏感参数名。
     *
     * <p>兼容 `sensitiveFields` 和 `sensitiveArgumentNames` 两种字段，方便 Python ToolPlan 与未来 Java
     * 工具目录渐进对齐。这里只保留字段名，不保留字段值。</p>
     */
    private List<String> sensitiveArgumentNames(Map<String, Object> governanceHints) {
        if (governanceHints == null || governanceHints.isEmpty()) {
            return List.of();
        }
        Object raw = governanceHints.get("sensitiveArgumentNames");
        if (raw == null) {
            raw = governanceHints.get("sensitiveFields");
        }
        if (raw instanceof Collection<?> collection) {
            return collection.stream().map(String::valueOf).filter(value -> !value.isBlank()).sorted().toList();
        }
        if (raw != null && !String.valueOf(raw).isBlank()) {
            return List.of(String.valueOf(raw));
        }
        return List.of();
    }

    private String normalizeEnumName(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private void ensurePlanningEnabled() {
        if (!Boolean.TRUE.equals(properties.getEnabled())) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT, "Agent Runtime 当前未启用");
        }
        if (!Boolean.TRUE.equals(properties.getAsyncTaskCommandPlanningEnabled())) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "Agent 异步工具命令草案规划当前未启用");
        }
    }
}
