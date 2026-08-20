/**
 * @Author : Cui
 * @Date: 2026/06/05 00:00
 * @Description DataSmart Govern Backend - AgentSessionHandoffDagExecutionBridgeEventPublisher.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.execution;

import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolDagExecutionDryRunResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentSessionHandoffDagExecutionBridgePreviewRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentSessionHandoffDagExecutionBridgePreviewResponse;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventProjectionRecord;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventProjectionStore;
import com.czh.datasmart.govern.agent.service.session.AgentSessionStore;
import com.czh.datasmart.govern.agent.service.session.AgentSessionRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Handoff DAG 到 Tool DAG 桥接预览的 runtime event 发布器。
 *
 * <p>5.17 已经让控制面能够把 handoff DAG 上的 {@code tool-control} 选择翻译成 Tool DAG dry-run 预览，
 * 但如果这一步只存在于 HTTP 响应里，后续审计台、会话时间线、WebSocket replay 和运维排障就无法回答一个关键问题：
 * “用户或 Agent Host 是什么时候从多 Agent 协作图进入工具预检链路的？当时桥接是否可推进？”</p>
 *
 * <p>本类负责把这个“桥接预览已生成”的事实压缩成低敏 runtime event。这里刻意不把完整 dry-run item、outbox
 * request template、工具参数、执行路径、prompt、SQL、样例数据或详细原因写入事件，因为 runtime event 会被长期回放、
 * 导出、跨角色展示，也可能被未来的 Kafka/ClickHouse/OpenSearch 消费。商业化 Agent 平台应该让事件层保存可观察摘要，
 * 让敏感明细继续留在受权限控制的 audit/result/resource reference 查询里。</p>
 *
 * <p>职责拆分说明：桥接服务负责业务预览和响应组装，本发布器只负责事件摘要和投影写入。这样后续如果从内存投影迁移到
 * outbox/Kafka/持久化审计表，只需要替换发布策略，不需要重写桥接业务流。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentSessionHandoffDagExecutionBridgeEventPublisher {

    /**
     * 桥接预览事件 schema 版本。
     *
     * <p>schema 独立于 HTTP DTO，是因为事件会被 replay、审计导出和管理台时间线长期消费。即使将来响应对象新增字段，
     * 事件消费者也可以按版本稳定解析当前这批低敏摘要。</p>
     */
    public static final String SCHEMA_VERSION = "datasmart.agent-runtime.handoff-dag-execution-bridge-preview.v1";

    /**
     * runtime event 类型。
     *
     * <p>{@code previewed} 表示“桥接预览已经生成”，不表示工具已执行、不表示 outbox 已写入，也不表示审批已通过。
     * 这个命名能避免把控制面预检事实误解为执行事实。</p>
     */
    public static final String EVENT_TYPE = "agent.handoff_dag.execution_bridge.previewed";

    private static final String SOURCE = "JAVA_AGENT_RUNTIME";
    private static final String STAGE = "handoff_dag_execution_bridge_previewed";

    private final AgentRuntimeEventProjectionStore projectionStore;
    private final AgentSessionStore sessionStore;

    /**
     * 发布桥接预览事件。
     *
     * <p>该方法采用“尽力而为”策略：桥接预览 API 是当前用户交互链路，投影写入失败不应阻塞响应返回。生产阶段如果要把
     * 该事件提升为强审计事实，可以在这里接入事务 outbox 或专用审计表，再决定是否 fail-closed。</p>
     *
     * @param sessionId Agent 会话 ID，用于回填租户、项目、操作者和会话时间线查询维度。
     * @param runId Agent Run ID，用于把桥接预览事件挂到同一轮推理/执行链路。
     * @param traceId 当前 HTTP trace/request ID，便于从接口请求追踪到 runtime event。
     * @param request 原始桥接请求，只读取低敏选择器和配置开关，不写入工具参数。
     * @param response 已生成的桥接预览响应，只抽取摘要计数和状态，不写入完整模板。
     */
    public void publish(String sessionId,
                        String runId,
                        String traceId,
                        AgentSessionHandoffDagExecutionBridgePreviewRequest request,
                        AgentSessionHandoffDagExecutionBridgePreviewResponse response) {
        try {
            AgentRuntimeEventProjectionRecord record = buildRecord(sessionId, runId, traceId, request, response);
            boolean appended = projectionStore.append(record);
            if (!appended) {
                log.debug("Handoff DAG bridge preview runtime event 已存在，跳过去重写入，identityKey={}", record.identityKey());
            }
        } catch (RuntimeException ex) {
            log.warn("Handoff DAG bridge preview runtime event 写入失败，sessionId={}, runId={}, error={}",
                    sessionId, runId, ex.getMessage());
        }
    }

    private AgentRuntimeEventProjectionRecord buildRecord(String sessionId,
                                                          String runId,
                                                          String traceId,
                                                          AgentSessionHandoffDagExecutionBridgePreviewRequest request,
                                                          AgentSessionHandoffDagExecutionBridgePreviewResponse response) {
        Instant now = Instant.now();
        Optional<AgentSessionRecord> session = sessionStore.findById(sessionId);
        String tenantId = session.map(AgentSessionRecord::getTenantId).map(String::valueOf).orElse(null);
        String projectId = session.map(AgentSessionRecord::getProjectId).map(String::valueOf).orElse(null);
        String actorId = session.map(AgentSessionRecord::getActorId).orElse(null);
        return new AgentRuntimeEventProjectionRecord(
                identityKey(tenantId, projectId, actorId, sessionId, runId, request, response),
                SCHEMA_VERSION,
                SOURCE,
                EVENT_TYPE,
                STAGE,
                messageOf(response),
                severityOf(response),
                tenantId,
                projectId,
                actorId,
                traceId,
                runId,
                sessionId,
                null,
                now,
                now,
                now,
                attributesOf(request, response)
        );
    }

    /**
     * 构造桥接预览的稳定、低敏幂等键。
     *
     * <p>摘要同时绑定租户、项目、操作者、会话和 Run，防止不同治理范围内的相同预览相互去重；并纳入会改变事件摘要的
     * handoff/tool 选择、dry-run 指纹、批量限制和模板状态。traceId 故意不参与计算，因为网络重试通常会产生新的
     * traceId，但它们仍应被识别为同一次预览。最终只保存 SHA-256 值，不把会话、Run、节点或审计 ID 明文扩散到
     * runtime event identityKey。</p>
     */
    private String identityKey(String tenantId,
                               String projectId,
                               String actorId,
                               String sessionId,
                               String runId,
                               AgentSessionHandoffDagExecutionBridgePreviewRequest request,
                               AgentSessionHandoffDagExecutionBridgePreviewResponse response) {
        AgentRunToolDagExecutionDryRunResponse dryRun = response.dryRun();
        StringBuilder material = new StringBuilder();
        appendIdentityValue(material, SCHEMA_VERSION);
        appendIdentityValue(material, SOURCE);
        appendIdentityValue(material, EVENT_TYPE);
        appendIdentityValue(material, tenantId);
        appendIdentityValue(material, projectId);
        appendIdentityValue(material, actorId);
        appendIdentityValue(material, sessionId);
        appendIdentityValue(material, runId);
        appendIdentityValue(material, response.bridgeAction());
        appendIdentityValue(material, response.bridgeReady());
        appendSortedIdentityList(material, response.handoffNodeIds());
        appendSortedIdentityList(material, response.mappedToolNodeIds());
        appendSortedIdentityList(material, response.mappedToolAuditIds());
        appendIdentityValue(material, dryRun.selectionFingerprint());
        appendIdentityValue(material, dryRun.requestedMaxNodes());
        appendIdentityValue(material, request == null ? null : request.includeUnselectedPreviewItems());
        appendIdentityValue(material, !response.selectedNodeOutboxRequestTemplate().isEmpty());
        appendIdentityValue(material, templateListSize(response, "nodeIds"));
        appendIdentityValue(material, templateListSize(response, "auditIds"));
        return "handoff-bridge-preview:sha256:" + sha256(material.toString());
    }

    /** 使用长度前缀编码，避免不同字段组合在摘要输入中产生边界歧义。 */
    private void appendIdentityValue(StringBuilder material, Object value) {
        String text = value == null ? "" : String.valueOf(value);
        material.append(text.length()).append(':').append(text).append(';');
    }

    /** 选择器按集合语义排序，避免调用方只调整展示顺序就绕过去重。 */
    private void appendSortedIdentityList(StringBuilder material, List<String> values) {
        List<String> safeValues = safeList(values);
        appendIdentityValue(material, safeValues.size());
        safeValues.stream().sorted().forEach(value -> appendIdentityValue(material, value));
    }

    /** 对内部规范串计算不可逆摘要，identityKey 不保存参与计算的明文。 */
    private String sha256(String material) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256，无法生成 handoff bridge preview 幂等键", exception);
        }
    }

    private String messageOf(AgentSessionHandoffDagExecutionBridgePreviewResponse response) {
        AgentRunToolDagExecutionDryRunResponse dryRun = response.dryRun();
        return "Handoff DAG 桥接预览已生成：bridgeAction=" + response.bridgeAction()
                + "，可推进=" + response.bridgeReady()
                + "，选中工具候选 " + dryRun.selectedCount()
                + " 个，异步入箱预览 " + dryRun.asyncEnqueuePreviewCount()
                + " 个，阻断 " + dryRun.blockedCount()
                + " 个。";
    }

    private String severityOf(AgentSessionHandoffDagExecutionBridgePreviewResponse response) {
        if (!response.bridgeReady()) {
            return "audit";
        }
        AgentRunToolDagExecutionDryRunResponse dryRun = response.dryRun();
        if ((dryRun.blockedCount() != null && dryRun.blockedCount() > 0)
                || (dryRun.notFoundCount() != null && dryRun.notFoundCount() > 0)) {
            return "audit";
        }
        return "info";
    }

    private Map<String, Object> attributesOf(AgentSessionHandoffDagExecutionBridgePreviewRequest request,
                                             AgentSessionHandoffDagExecutionBridgePreviewResponse response) {
        AgentRunToolDagExecutionDryRunResponse dryRun = response.dryRun();
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("previewOnly", true);
        attributes.put("bridgeReady", response.bridgeReady());
        attributes.put("bridgeAction", response.bridgeAction());
        attributes.put("handoffNodeIds", safeList(response.handoffNodeIds()));
        attributes.put("mappedToolNodeIds", safeList(response.mappedToolNodeIds()));
        attributes.put("mappedToolAuditIds", safeList(response.mappedToolAuditIds()));
        attributes.put("handoffNodeCount", safeList(response.handoffNodeIds()).size());
        attributes.put("mappedToolNodeCount", safeList(response.mappedToolNodeIds()).size());
        attributes.put("mappedToolAuditCount", safeList(response.mappedToolAuditIds()).size());
        attributes.put("requestedMaxNodes", request == null ? null : request.maxNodes());
        attributes.put("includeUnselectedPreviewItems", request != null && Boolean.TRUE.equals(request.includeUnselectedPreviewItems()));
        attributes.put("templateGenerated", !response.selectedNodeOutboxRequestTemplate().isEmpty());
        attributes.put("templateAsyncNodeCount", templateListSize(response, "nodeIds"));
        attributes.put("templateAsyncAuditCount", templateListSize(response, "auditIds"));
        attributes.put("templateConfirmedDefault", response.selectedNodeOutboxRequestTemplate().get("confirmed"));
        attributes.put("selectionFingerprint", dryRun.selectionFingerprint());
        attributes.put("selectedCount", dryRun.selectedCount());
        attributes.put("syncDryRunCandidateCount", dryRun.syncDryRunCandidateCount());
        attributes.put("asyncEnqueuePreviewCount", dryRun.asyncEnqueuePreviewCount());
        attributes.put("blockedCount", dryRun.blockedCount());
        attributes.put("notSelectedCount", dryRun.notSelectedCount());
        attributes.put("notFoundCount", dryRun.notFoundCount());
        attributes.put("batchLimitReachedCount", dryRun.batchLimitReachedCount());
        attributes.put("summaryReasonCount", response.summaryReasons() == null ? 0 : response.summaryReasons().size());
        attributes.put("recommendedActionCount", response.recommendedActions() == null ? 0 : response.recommendedActions().size());
        /*
         * 该策略字段是给前端、审计台和未来事件消费者看的“安全边界声明”。
         * 只要事件里出现 prompt、toolArguments、SQL、executionPath、targetEndpoint、完整 requestTemplate 等字段，
         * 就说明本发布器越过了低敏摘要边界，测试也会同步保护这一点。
         */
        attributes.put("eventPayloadPolicy", "SUMMARY_ONLY_NO_TOOL_ARGS_NO_PROMPT_NO_EXECUTION_PATH_NO_TEMPLATE_BODY");
        return Collections.unmodifiableMap(attributes);
    }

    private int templateListSize(AgentSessionHandoffDagExecutionBridgePreviewResponse response, String key) {
        Object value = response.selectedNodeOutboxRequestTemplate().get(key);
        return value instanceof List<?> list ? list.size() : 0;
    }

    private List<String> safeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }
}
