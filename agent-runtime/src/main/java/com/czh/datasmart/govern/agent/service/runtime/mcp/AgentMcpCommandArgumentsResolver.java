/**
 * @Author : Cui
 * @Date: 2026/07/03 19:45
 * @Description DataSmart Govern Backend - AgentMcpCommandArgumentsResolver.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime.mcp;

import com.czh.datasmart.govern.agent.config.AgentMcpDurableWorkerClientProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolPlanArgumentsPayloadView;
import com.czh.datasmart.govern.agent.event.command.AgentAsyncTaskCommandOutboxRecord;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionState;
import com.czh.datasmart.govern.agent.service.AgentToolPlanArgumentsPayloadService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * MCP command 真实参数的“临执行解析器”。
 *
 * <p>正式 MCP producer 只在 PostgreSQL outbox 中保存
 * {@code agent-tool-audit://.../plan-arguments} 引用、参数名和低敏治理事实，不保存参数值。本服务在
 * dispatcher 已领取 command、即将调用 Python Runtime 时，才通过 {@link AgentToolPlanArgumentsPayloadService}
 * 读取审计快照，并执行第二次身份边界校验。</p>
 *
 * <p>为什么不能只凭 payloadReference 直接读取：</p>
 * <p>1. 引用字符串可能被历史脚本、错误迁移或人工补偿写错，必须与服务端重新生成的引用精确一致；</p>
 * <p>2. command 与 audit 必须同时匹配 session、run、audit、tenant、project、workspace 和 actor，
 * 否则可能发生跨租户或跨运行参数错配；</p>
 * <p>3. toolCode 必须一致，防止把低风险工具的参数交给另一个高风险 MCP 工具；</p>
 * <p>4. 审计状态必须仍为 PLANNED，避免重复消费已经执行、失败、取消或被拒绝的调用；</p>
 * <p>5. 参数大小必须受限，避免 dispatcher/Python Runtime 因超大 JSON 发生内存放大或长时间阻塞。</p>
 *
 * <p>兼容边界：历史测试或灰度 command 可能已经把 {@code arguments/toolArguments} 内联进 payload。
 * 解析器暂时允许读取这两种旧字段，但仍执行大小限制。新的正式 producer 不会再写入这些字段；
 * 待历史 outbox 全部消费或清理后，可以删除该兼容分支。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentMcpCommandArgumentsResolver {

    private final AgentToolPlanArgumentsPayloadService payloadService;
    private final AgentMcpDurableWorkerClientProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * 为单条 MCP command 解析短生命周期参数。
     *
     * @param record 当前 dispatcher 领取的 durable outbox 记录
     * @param internalToolName 即将调用的内部 MCP 工具名
     * @param commandPayload 已经过 JSON object 校验的低敏 command payload
     * @return 参数 Map 的防御性副本；调用链结束后不应缓存、记录日志或写回 outbox
     */
    public Map<String, Object> resolve(
            AgentAsyncTaskCommandOutboxRecord record,
            String internalToolName,
            Map<String, Object> commandPayload) {
        requireRecord(record);
        Map<String, Object> payload = commandPayload == null ? Map.of() : commandPayload;
        Map<String, Object> legacyInline = legacyInlineArguments(payload);
        if (legacyInline != null) {
            return validateSize(legacyInline);
        }

        AgentToolPlanArgumentsPayloadView resolved = payloadService.getPlanArgumentsPayload(
                record.sessionId(),
                record.runId(),
                record.auditId()
        );
        validateResolvedBoundaries(record, internalToolName, resolved);
        return validateSize(resolved.planArguments());
    }

    /**
     * 对引用解析结果执行全边界一致性校验。
     *
     * <p>校验失败只返回字段级低敏错误，不拼接参数值，也不输出 payloadReference 全文之外的业务正文。</p>
     */
    private void validateResolvedBoundaries(
            AgentAsyncTaskCommandOutboxRecord record,
            String internalToolName,
            AgentToolPlanArgumentsPayloadView resolved) {
        if (resolved == null) {
            throw new IllegalStateException("MCP 参数引用解析结果为空");
        }
        requireEqual("payloadReference", record.payloadReference(), resolved.payloadReference());
        requireEqual("sessionId", record.sessionId(), resolved.sessionId());
        requireEqual("runId", record.runId(), resolved.runId());
        requireEqual("auditId", record.auditId(), resolved.auditId());
        requireEqual("toolCode", internalToolName, resolved.toolCode());
        requireEqual("tenantId", record.tenantId(), resolved.tenantId());
        requireEqual("projectId", record.projectId(), resolved.projectId());
        requireEqual("workspaceId", record.workspaceId(), resolved.workspaceId());
        requireEqual("actorId", record.actorId(), resolved.actorId());
        if (!AgentToolExecutionState.PLANNED.name().equals(resolved.state())) {
            throw new IllegalStateException(
                    "MCP 参数引用对应的工具审计已不处于 PLANNED，禁止重复或越过状态机执行"
            );
        }
    }

    /**
     * 读取历史内联参数。
     *
     * @return 没有旧字段时返回 null；存在字段时必须是 JSON object
     */
    private Map<String, Object> legacyInlineArguments(Map<String, Object> payload) {
        for (String field : new String[]{"arguments", "toolArguments"}) {
            if (!payload.containsKey(field)) {
                continue;
            }
            Object value = payload.get(field);
            if (!(value instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("历史 MCP command 的 " + field + " 必须是 JSON object");
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(String.valueOf(key), item));
            return copy;
        }
        return null;
    }

    /**
     * 限制解析后参数的 JSON 字节数。
     *
     * <p>使用 JSON 序列化后的 UTF-8 大小，而不是 Map.size()，因为一个字段也可能包含超长字符串或深层数组。</p>
     */
    private Map<String, Object> validateSize(Map<String, Object> arguments) {
        Map<String, Object> copy = arguments == null ? new LinkedHashMap<>() : new LinkedHashMap<>(arguments);
        try {
            int bytes = objectMapper.writeValueAsString(copy).getBytes(StandardCharsets.UTF_8).length;
            if (bytes > Math.max(1, properties.getMaxResolvedArgumentsBytes())) {
                throw new IllegalArgumentException(
                        "MCP 参数超过临执行解析大小上限，argumentsBytes=" + bytes
                );
            }
            return copy;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("MCP 参数无法序列化为 JSON object，参数正文已隐藏");
        }
    }

    private void requireRecord(AgentAsyncTaskCommandOutboxRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("MCP command outbox record 不能为空");
        }
    }

    private void requireEqual(String field, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw new IllegalStateException("MCP command 与审计参数快照的 " + field + " 不一致");
        }
    }
}
