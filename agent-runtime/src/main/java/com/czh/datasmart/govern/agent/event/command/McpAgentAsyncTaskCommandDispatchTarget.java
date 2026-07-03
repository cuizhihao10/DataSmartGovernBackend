/**
 * @Author : Cui
 * @Date: 2026/07/03 17:12
 * @Description DataSmart Govern Backend - McpAgentAsyncTaskCommandDispatchTarget.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.event.command;

import com.czh.datasmart.govern.agent.config.AgentAsyncTaskCommandOutboxProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentCommandWorkerLeaseClaimRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentCommandWorkerLeaseReleaseRequest;
import com.czh.datasmart.govern.agent.service.runtime.AgentCommandWorkerLeaseClaimResult;
import com.czh.datasmart.govern.agent.service.runtime.AgentCommandWorkerLeaseRecord;
import com.czh.datasmart.govern.agent.service.runtime.AgentCommandWorkerLeaseService;
import com.czh.datasmart.govern.agent.service.runtime.mcp.AgentMcpDurableWorkerCallResult;
import com.czh.datasmart.govern.agent.service.runtime.mcp.AgentMcpDurableWorkerClient;
import com.czh.datasmart.govern.agent.service.runtime.mcp.AgentMcpDurableWorkerRunRequest;
import com.czh.datasmart.govern.agent.service.runtime.mcp.AgentMcpCommandArgumentsResolver;
import com.czh.datasmart.govern.agent.service.runtime.mcp.AgentMcpWorkerReceiptIngestionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 MCP 专用 command outbox 记录投递给 Python MCP Durable Worker。
 *
 * <p>本类位于 outbox dispatcher 的传输适配层，不负责创建 command，也不负责重新授权。它只做三件事：</p>
 * <p>1. 判断一条 command 是否属于 MCP 执行面；</p>
 * <p>2. 从 outbox record 与白名单 payload 字段构造 Java/Python 的窄请求合同；</p>
 * <p>3. 调用 {@link AgentMcpDurableWorkerClient}，只有 Python 内部 API 明确接受后才正常返回。</p>
 *
 * <p>失败语义由现有 dispatcher 统一接管：本 target 抛出异常后，dispatcher 会把记录写回 FAILED、计算退避时间，并在超过
 * 最大尝试次数后进入 BLOCKED。这样 MCP 执行不会另起一套重试状态机，也不会绕过现有人工 requeue/dead-letter/ignore
 * 运维入口。</p>
 *
 * <p>安全边界：</p>
 * <p>1. 不把整个 payload 复制到 controlFacts，只读取明确白名单字段；</p>
 * <p>2. 不根据 confirmationId 猜测 permissionGranted/approvalVerified；这两个布尔事实必须由上游显式写入；</p>
 * <p>3. arguments 只允许来自 payload 的 arguments/toolArguments 对象，调用结果和异常消息不回显参数；</p>
 * <p>4. 不在本阶段写 Java receipt，receipt 映射会由下一层独立服务完成，避免传输层同时承担持久化副作用。</p>
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "datasmart.agent-runtime.mcp-durable-worker",
        name = "enabled",
        havingValue = "true"
)
public class McpAgentAsyncTaskCommandDispatchTarget implements AgentAsyncTaskCommandDispatchTarget {

    public static final String MCP_CONSUMER_SERVICE = "python-ai-runtime-mcp-client";

    private final AgentAsyncTaskCommandOutboxProperties outboxProperties;
    private final AgentMcpDurableWorkerClient workerClient;
    private final AgentCommandWorkerLeaseService workerLeaseService;
    private final AgentMcpWorkerReceiptIngestionService receiptIngestionService;
    private final AgentMcpCommandArgumentsResolver argumentsResolver;
    private final ObjectMapper objectMapper;

    @Override
    public String targetName() {
        return "python-ai-runtime:mcp-durable-worker";
    }

    /**
     * 识别 MCP 专用 command。
     *
     * <p>优先使用稳定的 {@code toolCode=mcp.*} 命名空间；同时兼容 targetService/consumerService，便于旧 producer 或
     * 灰度期间尚未统一 toolCode 的命令仍能被正确路由。非 MCP command 返回 false，dispatcher 不会调用本 target。</p>
     */
    @Override
    public boolean supports(AgentAsyncTaskCommandOutboxRecord record) {
        return record != null && (
                startsWithMcp(record.toolCode())
                        || MCP_CONSUMER_SERVICE.equalsIgnoreCase(record.targetService())
                        || MCP_CONSUMER_SERVICE.equalsIgnoreCase(record.consumerService())
        );
    }

    /**
     * 投递一条 MCP command。
     *
     * <p>Python 返回 HTTP 成功但 {@code accepted=false} 时仍视为投递失败，因为 Java 不能把“下游明确未接受”标成 PUBLISHED。
     * Python admission 失败但内部 handler 已正常生成 FAILED_PRECHECK receipt 的场景，当前 Python API 会返回 accepted=true，
     * 因此 outbox 可以进入 PUBLISHED，下一步由 receipt 写入服务记录最终执行事实。</p>
     */
    @Override
    public void dispatch(AgentAsyncTaskCommandOutboxRecord record) {
        if (!supports(record)) {
            return;
        }
        AgentCommandWorkerLeaseRecord lease = claimLease(record);
        boolean completed = false;
        try {
            AgentMcpDurableWorkerCallResult result = workerClient.run(
                    withLeaseFacts(toWorkerRequest(record), lease)
            );
            if (!result.attempted() || result.skipped()) {
                throw new IllegalStateException("MCP Durable Worker 未执行，errorCode=" + safeCode(result.errorCode()));
            }
            if (!result.accepted()) {
                throw new IllegalStateException("MCP Durable Worker 未接受命令，errorCode=" + safeCode(result.errorCode())
                        + ", statusCode=" + result.statusCode());
            }
            if (result.response() == null
                    || !containsArgumentsNeverReturnedPolicy(result.response().payloadPolicy())) {
                throw new IllegalStateException("MCP Durable Worker 响应缺少参数不回显安全策略声明");
            }
            /*
             * 必须先写 receipt 再返回。dispatcher 只有在本方法正常返回后才会把 outbox 标记为 PUBLISHED，
             * 因此 PUBLISHED 现在表示“Python 已处理 + Java 已完成 lease/status 校验并物化 receipt”，而不只是 HTTP 200。
             */
            receiptIngestionService.ingest(record, result.response());
            completed = true;
        } finally {
            releaseLease(record, lease, completed ? "COMPLETED" : "FAILED");
        }
    }

    /**
     * 在真实 MCP 调用前领取 Java command worker lease。
     *
     * <p>lease 是防止 dispatcher 重试、旧实例恢复或多副本并发时重复执行同一 MCP command 的 fencing 边界。
     * 只有当前 lease 持有者才能让 Java receipt service 接受 sideEffectStarted/Executed=true 的回执。</p>
     */
    private AgentCommandWorkerLeaseRecord claimLease(AgentAsyncTaskCommandOutboxRecord record) {
        AgentCommandWorkerLeaseClaimResult result = workerLeaseService.claim(
                record.sessionId(),
                record.runId(),
                new AgentCommandWorkerLeaseClaimRequest(
                        record.commandId(),
                        "python-mcp-durable-worker",
                        record.tenantId(),
                        record.projectId(),
                        parseLong(record.actorId()),
                        120
                )
        );
        if (!result.acquired() || !result.tokenVisible() || result.record() == null
                || !hasText(result.record().fencingToken())) {
            throw new IllegalStateException("MCP command worker lease 当前不可获取，等待 outbox 退避重试");
        }
        return result.record();
    }

    /**
     * 把 Java 签发的 lease 证据加入本次 Python control facts。
     *
     * <p>这些字段只存在于 Java -> Python 请求和 Python -> Java receipt 回程，不进入普通 API、runtime event 明文或
     * Prometheus label。Python 不修改、不生成 token，只负责原样回传。</p>
     */
    private AgentMcpDurableWorkerRunRequest withLeaseFacts(
            AgentMcpDurableWorkerRunRequest request,
            AgentCommandWorkerLeaseRecord lease) {
        Map<String, Object> facts = new LinkedHashMap<>(request.controlFacts());
        facts.put("fencingToken", lease.fencingToken());
        facts.put("workerLeaseVersion", lease.leaseVersion());
        facts.put("workerLeaseExpiresAtMs", lease.leaseExpiresAt().toEpochMilli());
        facts.put("workerLeaseRequired", true);
        return new AgentMcpDurableWorkerRunRequest(
                request.serverId(),
                request.internalToolName(),
                request.arguments(),
                facts,
                request.fallbackContext(),
                request.postToJava(),
                request.sessionId(),
                request.traceId(),
                request.toolCallId(),
                request.workspaceKey(),
                request.currentWorkspaceKey(),
                request.includeModelFeedback()
        );
    }

    private void releaseLease(AgentAsyncTaskCommandOutboxRecord record,
                              AgentCommandWorkerLeaseRecord lease,
                              String reason) {
        workerLeaseService.release(
                record.sessionId(),
                record.runId(),
                new AgentCommandWorkerLeaseReleaseRequest(
                        record.commandId(),
                        lease.executorId(),
                        lease.fencingToken(),
                        lease.leaseVersion(),
                        reason,
                        record.tenantId(),
                        record.projectId(),
                        parseLong(record.actorId())
                )
        );
    }

    /**
     * 将 outbox record 转为 Python worker 请求。
     *
     * <p>record 本身提供稳定路由事实；payload 只补充 MCP server、arguments、permission/approval/readiness 等协议字段。
     * 本方法不会把 payloadReference 指向的正文读取出来，也不会从 artifact、checkpoint 或审计表重建参数。</p>
     */
    AgentMcpDurableWorkerRunRequest toWorkerRequest(AgentAsyncTaskCommandOutboxRecord record) {
        Map<String, Object> payload = parsePayload(record.payloadJson());
        /*
         * 正式 MCP command 的 payload 只包含低敏控制事实和 payloadReference，不包含 arguments。
         * argumentsResolver 会在这一刻回读审计快照并重验租户/项目/workspace/actor/tool/state；解析结果只进入
         * 当前 worker request。历史 command 的内联参数兼容也集中在 resolver 内，避免传输层继续扩散两套逻辑。
         */
        String internalToolName = firstText(payload, "internalToolName", "toolCode");
        if (!hasText(internalToolName)) {
            internalToolName = record.toolCode();
        }
        if (!startsWithMcp(internalToolName)) {
            throw new IllegalArgumentException("MCP command 缺少合法 internalToolName");
        }
        String serverId = firstText(payload, "serverId", "mcpServerId");
        if (!hasText(serverId)) {
            serverId = serverIdFromToolName(internalToolName);
        }
        Map<String, Object> arguments = argumentsResolver.resolve(record, internalToolName, payload);

        Map<String, Object> controlFacts = new LinkedHashMap<>();
        putText(controlFacts, "tenantId", record.tenantId());
        putText(controlFacts, "projectId", record.projectId());
        putText(controlFacts, "workspaceKey", firstValue(payload, "workspaceKey", "workspaceId", record.workspaceId()));
        putText(controlFacts, "actorId", record.actorId());
        putText(controlFacts, "runId", record.runId());
        String callId = firstText(payload, "callId", "toolCallId");
        putText(controlFacts, "callId", hasText(callId) ? callId : record.commandId());
        putText(controlFacts, "commandId", record.commandId());
        putText(controlFacts, "auditId", record.auditId());
        putText(controlFacts, "outboxMessageId", record.outboxId());
        putText(controlFacts, "payloadReference", record.payloadReference());
        putText(controlFacts, "source", "JAVA_AGENT_RUNTIME_COMMAND_OUTBOX");
        controlFacts.put("allowedInternalToolNames", List.of(internalToolName));

        copyBoolean(payload, controlFacts, "permissionGranted");
        copyBoolean(payload, controlFacts, "approvalVerified");
        copyText(payload, controlFacts, "approvalConfirmationId");
        copyText(payload, controlFacts, "commandProposalId");
        copyText(payload, controlFacts, "checkpointId");
        String readiness = firstText(payload, "readinessDecision", "decision");
        if (!hasText(readiness) && outboxProperties.isDispatcherPreCheckEnabled()) {
            readiness = "READY";
        }
        putText(controlFacts, "readinessDecision", readiness);

        Map<String, Object> fallbackContext = new LinkedHashMap<>();
        putText(fallbackContext, "tenantId", record.tenantId());
        putText(fallbackContext, "projectId", record.projectId());
        putText(fallbackContext, "workspaceKey", firstValue(payload, "workspaceKey", "workspaceId", record.workspaceId()));
        putText(fallbackContext, "actorId", record.actorId());
        putText(fallbackContext, "runId", record.runId());

        return new AgentMcpDurableWorkerRunRequest(
                requireText(serverId, "serverId"),
                internalToolName,
                arguments,
                controlFacts,
                fallbackContext,
                null,
                record.sessionId(),
                record.traceId(),
                hasText(firstText(payload, "toolCallId", "callId"))
                        ? firstText(payload, "toolCallId", "callId")
                        : record.commandId(),
                text(firstValue(payload, "workspaceKey", "workspaceId", record.workspaceId())),
                text(firstValue(payload, "currentWorkspaceKey", "workspaceKey", "workspaceId", record.workspaceId())),
                null
        );
    }

    private Map<String, Object> parsePayload(String payloadJson) {
        if (!hasText(payloadJson)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(payloadJson, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("MCP command payloadJson 不是合法 JSON，正文已隐藏");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Map<String, Object> payload, String... keys) {
        for (String key : keys) {
            Object value = payload.get(key);
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> copy = new LinkedHashMap<>();
                map.forEach((entryKey, entryValue) -> copy.put(String.valueOf(entryKey), entryValue));
                return copy;
            }
            if (value != null) {
                throw new IllegalArgumentException("MCP command arguments 必须是 JSON object");
            }
        }
        return Map.of();
    }

    private void copyBoolean(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.get(key) instanceof Boolean value) {
            target.put(key, value);
        }
    }

    private void copyText(Map<String, Object> source, Map<String, Object> target, String key) {
        putText(target, key, source.get(key));
    }

    private void putText(Map<String, Object> target, String key, Object value) {
        String normalized = text(value);
        if (normalized != null) {
            target.put(key, normalized);
        }
    }

    private Object firstValue(Map<String, Object> payload, Object... candidates) {
        for (Object candidate : candidates) {
            if (candidate instanceof String key && payload.containsKey(key)) {
                return payload.get(key);
            }
            if (!(candidate instanceof String)) {
                return candidate;
            }
        }
        return null;
    }

    private String firstText(Map<String, Object> payload, Object... candidates) {
        return text(firstValue(payload, candidates));
    }

    private String serverIdFromToolName(String internalToolName) {
        String[] parts = internalToolName.split("\\.");
        if (parts.length < 3 || !hasText(parts[1])) {
            throw new IllegalArgumentException("MCP internalToolName 无法解析 serverId");
        }
        return parts[1];
    }

    private String requireText(String value, String fieldName) {
        if (!hasText(value)) {
            throw new IllegalArgumentException("MCP command 缺少必填字段 " + fieldName);
        }
        return value.trim();
    }

    private boolean containsArgumentsNeverReturnedPolicy(String value) {
        return value != null && value.contains("MCP_ARGUMENTS_NEVER_RETURNED");
    }

    private boolean startsWithMcp(String value) {
        return value != null && value.trim().toLowerCase().startsWith("mcp.");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String normalized = String.valueOf(value).trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String safeCode(String value) {
        if (!hasText(value)) {
            return "UNKNOWN";
        }
        String normalized = value.trim().toUpperCase();
        return normalized.matches("[A-Z0-9_\\-]{1,120}") ? normalized : "UNKNOWN";
    }

    private Long parseLong(String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
