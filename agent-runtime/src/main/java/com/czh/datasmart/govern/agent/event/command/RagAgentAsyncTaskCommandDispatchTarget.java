/**
 * @Author : Cui
 * @Date: 2026/07/05 01:22
 * @Description DataSmart Govern Backend - RagAgentAsyncTaskCommandDispatchTarget.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.event.command;

import com.czh.datasmart.govern.agent.service.runtime.rag.AgentRagCommandWorkerCallResult;
import com.czh.datasmart.govern.agent.service.runtime.rag.AgentRagCommandWorkerClient;
import com.czh.datasmart.govern.agent.service.runtime.rag.AgentRagCommandWorkerReceiptIngestionService;
import com.czh.datasmart.govern.agent.service.runtime.rag.AgentRagCommandWorkerRunRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 将 `knowledge.rag.query` command outbox 记录投递给 Python RAG Command Worker。
 *
 * <p>本类位于 outbox dispatcher 的传输适配层，不负责创建 command，也不负责生成 RAG 答案。
 * 它只做三件事：</p>
 *
 * <p>1. 识别属于 RAG 执行面的 command，避免它误入 task-management 通用 async-task inbox；</p>
 * <p>2. 从低敏 outbox envelope 中构造 Java/Python worker 请求合同；</p>
 * <p>3. 调用 Python RAG worker，并把 `javaReceiptPayload` 交给 Java receipt 控制面落地。</p>
 *
 * <p>为什么 RAG target 不强制 claim command worker lease：
 * RAG 查询在当前产品边界中是只读检索，不声明 `sideEffectStarted/Executed=true`。
 * worker lease 的主要价值是保护 shell、MCP tools/call、写文件、创建任务等副作用动作不被旧 worker 重复写回。
 * 对只读 RAG 强行引入 lease 会增加恢复复杂度，却不会显著提升副作用安全性。
 * 如果未来 RAG worker 开始写入答案 artifact、更新长期记忆或触发下游任务，届时应在对应写入动作前引入 lease 或 artifact grant gate。</p>
 *
 * <p>短生命周期 question 规则：
 * Python RAG worker 必须拿到 question 才能检索，但 question 只能来自当前 worker request 的 `arguments.question`
 * 或等价字段。它不允许进入 receipt、runtime event、日志、错误信息或 Java projection。
 * 如果 outbox payload 只有 queryRef 而没有可解析的短生命周期 question，本 target 会 fail-closed，
 * 等待后续接入安全参数仓库或 artifact materialization，而不是用 queryRef 伪造问题正文。</p>
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "datasmart.agent-runtime.rag-command-worker",
        name = "enabled",
        havingValue = "true"
)
public class RagAgentAsyncTaskCommandDispatchTarget implements AgentAsyncTaskCommandDispatchTarget {

    public static final String RAG_TOOL_CODE = "knowledge.rag.query";
    public static final String RAG_CONSUMER_SERVICE = "python-ai-runtime-rag";
    private static final String PAYLOAD_POLICY_MARKER = "RAG_WORKER_SUMMARY_ONLY_NO_QUESTION";

    private final AgentRagCommandWorkerClient workerClient;
    private final AgentRagCommandWorkerReceiptIngestionService receiptIngestionService;
    private final ObjectMapper objectMapper;

    @Override
    public String targetName() {
        return "python-ai-runtime:rag-command-worker";
    }

    /**
     * 识别 RAG 专用 command。
     *
     * <p>这里故意只信任稳定工具编码 `knowledge.rag.query`，而不把 `consumerService/targetService`
     * 当作独立执行依据。原因是 consumer/target 字段更偏“路由提示”，在灰度发布、人工补偿、历史数据迁移或
     * producer bug 场景下更容易被写错；如果 RAG worker 只因为 targetService 写成 `python-ai-runtime-rag`
     * 就接管命令，非 RAG 工具可能绕过它原本的权限、租约、幂等和副作用保护链路。</p>
     *
     * <p>因此本方法采用 fail-closed 策略：只有 toolCode 明确等于 `knowledge.rag.query` 时才进入 RAG worker。
     * targetService/consumerService 仍然可以作为诊断字段留在 outbox record 中，但不能替代工具编码完成授权。</p>
     */
    @Override
    public boolean supports(AgentAsyncTaskCommandOutboxRecord record) {
        return record != null && isRagTool(record.toolCode());
    }

    /**
     * 投递一条 RAG command。
     *
     * <p>只有 Python 明确 `accepted=true` 且声明了低敏 payload policy，本方法才会继续写入 Java receipt。
     * 如果 Python 不可达、返回非 2xx、未接受命令或响应缺少低敏策略声明，异常会抛回 dispatcher，
     * dispatcher 再按已有 outbox 退避策略写回 FAILED/BLOCKED。</p>
     */
    @Override
    public void dispatch(AgentAsyncTaskCommandOutboxRecord record) {
        if (!supports(record)) {
            return;
        }
        AgentRagCommandWorkerCallResult result = workerClient.run(toWorkerRequest(record));
        if (!result.attempted() || result.skipped()) {
            throw new IllegalStateException("RAG Command Worker 未执行，errorCode=" + safeCode(result.errorCode()));
        }
        if (!result.accepted()) {
            throw new IllegalStateException("RAG Command Worker 未接受命令，errorCode=" + safeCode(result.errorCode())
                    + ", statusCode=" + result.statusCode());
        }
        if (result.response() == null || !containsRagSummaryOnlyPolicy(result.response().payloadPolicy())) {
            throw new IllegalStateException("RAG Command Worker 响应缺少低敏 payloadPolicy 声明");
        }
        receiptIngestionService.ingest(record, result.response());
    }

    /**
     * 将 outbox record 转为 Python RAG worker 请求。
     *
     * <p>record 提供稳定路由事实；payload 只补充 RAG 执行所需的短生命周期 question、queryRef、检索预算和
     * checkpoint/artifact 低敏定位符。本方法不会读取 payloadReference 指向的正文，也不会从日志、checkpoint
     * 或模型输出中重建 question。</p>
     */
    AgentRagCommandWorkerRunRequest toWorkerRequest(AgentAsyncTaskCommandOutboxRecord record) {
        Map<String, Object> payload = parsePayload(record.payloadJson());
        Map<String, Object> inlineArguments = objectMap(payload, "arguments", "toolArguments");

        String question = firstText(payload, "question", "query", "objective");
        if (!hasText(question)) {
            question = firstText(inlineArguments, "question", "query", "objective");
        }
        if (!hasText(question)) {
            throw new IllegalArgumentException(
                    "RAG command 缺少短生命周期 question 输入；不能只凭 queryRef 执行真实检索"
            );
        }

        Map<String, Object> arguments = new LinkedHashMap<>(inlineArguments);
        arguments.put("question", question);
        putIfPresent(arguments, "topK", firstValue(payload, "topK", "top_k"));
        putIfPresent(arguments, "candidateLimit", firstValue(payload, "candidateLimit", "candidate_limit"));
        putIfPresent(arguments, "maxContextChars", firstValue(payload, "maxContextChars", "max_context_chars"));
        /*
         * 默认不强制生成答案正文。当前项目还未把 answer body 写入 MinIO/受控 artifact store，
         * 因此 dispatcher 路径先偏向“检索证据 + 低敏 receipt + checkpoint 可观测”。
         * 调用方如果已经具备 artifact writer，可显式传入 generateAnswer=true。
         */
        arguments.putIfAbsent("generateAnswer", firstValue(payload, "generateAnswer", "generate_answer", false));

        Map<String, Object> controlFacts = new LinkedHashMap<>();
        putText(controlFacts, "tenantId", record.tenantId());
        putText(controlFacts, "projectId", record.projectId());
        putText(controlFacts, "workspaceKey", firstValue(payload, "workspaceKey", "workspaceId", record.workspaceId()));
        putText(controlFacts, "actorId", record.actorId());
        putText(controlFacts, "sessionId", record.sessionId());
        putText(controlFacts, "runId", record.runId());
        putText(controlFacts, "commandId", record.commandId());
        putText(controlFacts, "auditId", record.auditId());
        putText(controlFacts, "outboxMessageId", record.outboxId());
        putText(controlFacts, "payloadReference", record.payloadReference());
        putText(controlFacts, "traceId", record.traceId());
        putText(controlFacts, "source", "JAVA_AGENT_RUNTIME_COMMAND_OUTBOX");
        putText(controlFacts, "toolCode", RAG_TOOL_CODE);
        putText(controlFacts, "queryRef", resolveQueryRef(payload, inlineArguments));
        putText(controlFacts, "answerArtifactReference",
                firstValue(payload, "answerArtifactReference", "answer_artifact_reference", "artifactReference"));
        putText(controlFacts, "artifactReferenceType",
                firstValue(payload, "artifactReferenceType", "artifact_reference_type"));
        String retrievalPolicyVersion = firstText(payload, "retrievalPolicyVersion", "retrieval_policy_version");
        putText(controlFacts, "retrievalPolicyVersion",
                hasText(retrievalPolicyVersion) ? retrievalPolicyVersion : "rag-policy.v1");
        putText(controlFacts, "idempotencyKey", record.idempotencyKey());
        String langGraphThreadId = firstText(payload, "langGraphThreadId", "langgraphThreadId", "lang_graph_thread_id");
        putText(controlFacts, "langGraphThreadId", hasText(langGraphThreadId)
                ? langGraphThreadId
                : "rag-command-worker:" + record.runId() + ":" + record.commandId());
        controlFacts.put("postToJava", false);

        return new AgentRagCommandWorkerRunRequest(arguments, controlFacts, false);
    }

    private Map<String, Object> parsePayload(String payloadJson) {
        if (!hasText(payloadJson)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(payloadJson, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("RAG command payloadJson 不是合法 JSON，正文已隐藏");
        }
    }

    private Map<String, Object> objectMap(Map<String, Object> payload, String... keys) {
        for (String key : keys) {
            Object value = payload.get(key);
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> copy = new LinkedHashMap<>();
                map.forEach((entryKey, entryValue) -> copy.put(String.valueOf(entryKey), entryValue));
                return copy;
            }
            if (value != null) {
                throw new IllegalArgumentException("RAG command arguments 必须是 JSON object");
            }
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private String resolveQueryRef(Map<String, Object> payload, Map<String, Object> arguments) {
        Object direct = firstValue(payload, "queryRef", "query_ref");
        if (direct == null) {
            direct = firstValue(arguments, "queryRef", "query_ref");
        }
        if (direct instanceof Map<?, ?> map) {
            Object digest = map.get("queryDigest");
            if (digest == null) {
                digest = map.get("digest");
            }
            String digestText = text(digest);
            if (hasText(digestText)) {
                return digestText.startsWith("sha256:")
                        ? "rag-query:" + digestText
                        : "rag-query:sha256:" + digestText;
            }
            return null;
        }
        return text(direct);
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
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

    private boolean containsRagSummaryOnlyPolicy(String value) {
        return value != null && value.contains(PAYLOAD_POLICY_MARKER);
    }

    private boolean isRagTool(String value) {
        return value != null && RAG_TOOL_CODE.equals(value.trim().toLowerCase(Locale.ROOT));
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
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return normalized.matches("[A-Z0-9_\\-]{1,120}") ? normalized : "UNKNOWN";
    }
}
