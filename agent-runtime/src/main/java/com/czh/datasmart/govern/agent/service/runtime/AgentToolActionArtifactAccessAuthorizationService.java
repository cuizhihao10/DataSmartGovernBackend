/**
 * @Author : Cui
 * @Date: 2026/06/24 17:53
 * @Description DataSmart Govern Backend - AgentToolActionArtifactAccessAuthorizationService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionArtifactAccessAuthorizationResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionArtifactAccessAuthorizeRequest;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 命令执行 artifact 访问预授权服务。
 *
 * <p>本服务承接 5.98 command worker lease fact 校验之后的收口工作：worker 已经能够证明“我有资格写回 receipt”，
 * 但后续读取 artifact 时仍然不能只相信一个字符串引用。真实商业化 Agent Host 中，artifact 往往可能包含命令输出、
 * 质量报告、ETL 结果、采样摘要或排障附件，哪怕引用本身是低敏的，也必须确认它确实来自当前租户、项目、run/session
 * 范围内的已登记 worker receipt。否则调用方只要猜到或伪造一个看起来合法的 `agent-artifact:` 字符串，就可能越权读取产物。</p>
 *
 * <p>这里采用“双闸门”模型：</p>
 * <p>第一道闸门在 agent-runtime：只验证低敏 artifactReference 是否能对上 runtime event 中的 command worker receipt 事实，
 * 并返回 metadata-only 预授权结果。</p>
 * <p>第二道闸门留给后续对象存储/制品服务：真正读取正文时，还要检查 MinIO/object-store ACL、DLP/恶意内容扫描、
 * 下载审计、保留期、导出水印和 human-in-the-loop 等策略。本服务不会返回正文、签名 URL、真实 bucket/key、
 * stdout/stderr、命令行、SQL、prompt、工具实参或模型输出。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentToolActionArtifactAccessAuthorizationService {

    /**
     * 当前接口的低敏载荷承诺。
     *
     * <p>把策略写成常量并返回给调用方，是为了让前端、审计系统和自动化测试都能确认“这个接口只做预授权”，
     * 不应该被误用成文件下载接口或命令输出查看接口。</p>
     */
    public static final String PAYLOAD_POLICY =
            "METADATA_ONLY_NO_ARTIFACT_BODY_NO_STDIO_NO_SIGNED_URL_NO_COMMAND_LINE_NO_PROMPT_NO_SQL_NO_TOKEN";

    private static final int HOT_WINDOW_QUERY_LIMIT = 1000;
    private static final String DEFAULT_ACCESS_MODE = "METADATA_ONLY";
    private static final String RAG_QUERY_COMPLETED_OUTCOME = "RAG_QUERY_COMPLETED";
    private static final String RAG_ANSWER_ARTIFACT_TYPE = "AGENT_RAG_ANSWER_ARTIFACT";
    private static final String RAG_QUERY_TOOL_CODE = "knowledge.rag.query";

    private final AgentRuntimeEventProjectionStore projectionStore;
    private final AgentRuntimeEventProjectionAccessSupport accessSupport;

    /**
     * 对 artifactReference 做访问预授权。
     *
     * @param request 调用方希望访问的 command/artifact 低敏引用，不允许携带正文或真实对象存储地址。
     * @param accessContext gateway/permission-admin 透传并解析后的访问范围；服务端会继续做租户、项目、actor 收口。
     * @return metadata-only 预授权结果；即使 authorized=true，bodyContentGranted 也保持 false。
     */
    public AgentToolActionArtifactAccessAuthorizationResponse authorize(
            AgentToolActionArtifactAccessAuthorizeRequest request,
            AgentRuntimeEventQueryAccessContext accessContext) {
        validateRequest(request);

        String commandId = safeText(request.commandId());
        String artifactReference = safeArtifactReference(request.artifactReference());
        String requestedAccessMode = normalizeAccessMode(request.requestedAccessMode());

        /*
         * 先构造用户主动过滤条件，再交给 AccessSupport 做服务端收口。
         * 这里的租户、项目、actor、run、session 只能缩小可见范围，不能扩大 gateway Header 中的授权范围。
         * 对 PROJECT 范围且没有授权项目的场景，InMemory/JDBC store 都会返回空结果，形成 deny-by-default 行为。
         */
        AgentRuntimeEventProjectionQuery scopedQuery = accessSupport.restrict(
                new AgentRuntimeEventProjectionQuery(
                        safeText(request.tenantId()),
                        safeText(request.projectId()),
                        safeText(request.actorId()),
                        null,
                        safeText(request.runId()),
                        safeText(request.sessionId()),
                        AgentToolActionCommandWorkerReceiptService.EVENT_TYPE,
                        null,
                        HOT_WINDOW_QUERY_LIMIT
                ),
                accessContext
        );

        List<AgentRuntimeEventProjectionRecord> candidates = projectionStore.query(scopedQuery);
        Optional<AgentRuntimeEventProjectionRecord> matched = candidates.stream()
                .filter(record -> matchesReceipt(record, commandId, artifactReference, request))
                .max(Comparator.comparingLong(record -> record.replaySequence() == null ? 0L : record.replaySequence()));

        if (matched.isEmpty()) {
            return deniedNoReceipt(commandId, artifactReference, requestedAccessMode);
        }

        AgentRuntimeEventProjectionRecord record = matched.get();
        if (!artifactAvailable(record)) {
            return deniedArtifactUnavailable(commandId, artifactReference, requestedAccessMode, record);
        }
        if (!artifactLifecycleEligible(record)) {
            return deniedLifecycleNotEligible(commandId, artifactReference, requestedAccessMode, record);
        }

        return authorizedMetadataOnly(commandId, artifactReference, requestedAccessMode, record);
    }

    /**
     * 校验请求字段是否满足“低敏引用预授权”的最低要求。
     *
     * <p>这里把无效引用视为 BAD_REQUEST，而不是返回普通 denied，原因是 URL、路径逃逸、JSON 片段、换行或 token
     * 这类输入本身就不应进入后续鉴权流程。越早拒绝，越能避免这些内容被日志、指标或异常排障链路扩散。</p>
     */
    private void validateRequest(AgentToolActionArtifactAccessAuthorizeRequest request) {
        if (request == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "artifact 访问预授权请求体不能为空");
        }
        if (safeText(request.commandId()) == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "artifact 访问预授权必须提供 commandId");
        }
        if (safeArtifactReference(request.artifactReference()) == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "artifactReference 只能使用受控低敏 scheme，不能包含 URL、真实路径、路径逃逸、对象正文或凭证片段");
        }
        rejectSensitiveText(request.artifactReferenceType(), "artifactReferenceType");
        rejectSensitiveText(request.requestedAccessMode(), "requestedAccessMode");
        rejectSensitiveText(request.toolCode(), "toolCode");
    }

    /**
     * 判断某条 command worker receipt 是否就是本次请求要校验的 artifact 事实。
     *
     * <p>runtime event 查询只能按 tenant/project/actor/run/session/eventType 等通用维度下沉；
     * commandId、artifactReference 和 toolCode 属于事件 attributes，因此在服务层做白名单过滤。
     * 注意这里仍然只读取低敏 attributes，不读取任何 payload/body/stdout/stderr。</p>
     */
    private boolean matchesReceipt(AgentRuntimeEventProjectionRecord record,
                                   String commandId,
                                   String artifactReference,
                                   AgentToolActionArtifactAccessAuthorizeRequest request) {
        Map<String, Object> attributes = record.attributes();
        if (!commandId.equals(text(attributes, "commandId"))) {
            return false;
        }
        if (!artifactReference.equals(text(attributes, "artifactReference"))) {
            return false;
        }
        String requestedType = safeText(request.artifactReferenceType());
        if (requestedType != null && !requestedType.equalsIgnoreCase(text(attributes, "artifactReferenceType"))) {
            return false;
        }
        String requestedToolCode = safeText(request.toolCode());
        return requestedToolCode == null || requestedToolCode.equals(text(attributes, "toolCode"));
    }

    /**
     * receipt 是否声明 artifact 元数据已经登记。
     *
     * <p>artifactReference 只是“指向某个产物”的低敏字符串；只有 receipt 同时声明 artifactAvailable=true，
     * 下游才有理由继续进入对象存储鉴权。否则可能只是 worker 在预检阶段写入的占位引用。</p>
     */
    private boolean artifactAvailable(AgentRuntimeEventProjectionRecord record) {
        return bool(record.attributes(), "artifactAvailable");
    }

    /**
     * 判断 artifact 所属生命周期是否已经进入可查询阶段。
     *
     * <p>执行前预检通过并不等于产物已经生成。当前允许 EXECUTION_SUCCEEDED、EXECUTION_FAILED、
     * COMPENSATION_REQUIRED 这类执行后或补偿相关状态进入 metadata-only 访问预授权；RAG_QUERY_COMPLETED
     * 只有在 toolCode/artifactReferenceType 同时表明它是只读 RAG answer artifact 时才允许进入下一道门。
     * FAILED_PRECHECK 和 WORKER_PRECHECK_PASSED 则不应开放 artifact 读取链路。</p>
     */
    private boolean artifactLifecycleEligible(AgentRuntimeEventProjectionRecord record) {
        String outcome = text(record.attributes(), "outcome");
        return "EXECUTION_SUCCEEDED".equals(outcome)
                || "EXECUTION_FAILED".equals(outcome)
                || "COMPENSATION_REQUIRED".equals(outcome)
                || ragReadOnlyAnswerArtifactEligible(record, outcome)
                || bool(record.attributes(), "sideEffectExecuted");
    }

    /**
     * RAG answer artifact 的生命周期例外。
     *
     * <p>RAG 查询是典型的只读 Agent 能力：Python worker 不应该声明 sideEffectStarted/sideEffectExecuted，
     * 否则会把“读取知识并生成答案”误解释成“执行了命令或写入了业务系统”。但它确实会把答案正文写入 MinIO/S3
     * 这类受控 artifact store，所以 Java 控制面需要允许 `RAG_QUERY_COMPLETED` 在 artifactAvailable=true 后
     * 进入 metadata-only 预授权链路。这里同时收口 toolCode 与 artifactReferenceType，避免任意自定义 outcome
     * 伪装成 RAG 查询来绕过普通命令产物生命周期门禁。</p>
     */
    private boolean ragReadOnlyAnswerArtifactEligible(AgentRuntimeEventProjectionRecord record, String outcome) {
        Map<String, Object> attributes = record.attributes();
        return RAG_QUERY_COMPLETED_OUTCOME.equals(outcome)
                && RAG_ANSWER_ARTIFACT_TYPE.equals(text(attributes, "artifactReferenceType"))
                && RAG_QUERY_TOOL_CODE.equals(text(attributes, "toolCode"));
    }

    private AgentToolActionArtifactAccessAuthorizationResponse authorizedMetadataOnly(
            String commandId,
            String artifactReference,
            String requestedAccessMode,
            AgentRuntimeEventProjectionRecord record) {
        return response(
                true,
                "METADATA_AUTHORIZED_BODY_REQUIRES_SECONDARY_STORE_AUTHORIZATION",
                commandId,
                artifactReference,
                requestedAccessMode,
                record,
                authorizedEvidenceCodes(record),
                List.of(),
                List.of(
                        "可以进入对象存储/制品服务的下一段鉴权，但当前接口不返回正文或签名 URL。",
                        "正文读取仍需校验对象存储权限、DLP/恶意内容扫描、下载审计和保留期策略。"
                )
        );
    }

    /**
     * 构造授权通过证据码。
     *
     * <p>普通命令 artifact 和 RAG answer artifact 共用同一条响应 DTO，但 RAG 的产品语义不同：
     * 它不是“副作用执行成功后的输出”，而是“只读检索/生成完成后的受控答案产物”。额外证据码能帮助前端、
     * 审计系统和回归测试识别这条路径，后续排查时也不用反推 outcome/toolCode/artifactType 的组合含义。</p>
     */
    private List<String> authorizedEvidenceCodes(AgentRuntimeEventProjectionRecord record) {
        List<String> evidenceCodes = new ArrayList<>(List.of(
                "RUNTIME_EVENT_SCOPE_RESTRICTED",
                "ARTIFACT_REFERENCE_SAFE_SCHEME",
                "COMMAND_WORKER_RECEIPT_MATCHED",
                "ARTIFACT_AVAILABLE_DECLARED",
                "ARTIFACT_BODY_NOT_RETURNED",
                "SIGNED_URL_NOT_ISSUED"
        ));
        if (ragReadOnlyAnswerArtifactEligible(record, text(record.attributes(), "outcome"))) {
            evidenceCodes.add("RAG_READ_ONLY_ANSWER_ARTIFACT_ELIGIBLE");
        }
        return List.copyOf(evidenceCodes);
    }

    private AgentToolActionArtifactAccessAuthorizationResponse deniedNoReceipt(
            String commandId,
            String artifactReference,
            String requestedAccessMode) {
        return response(
                false,
                "DENIED_NO_MATCHING_COMMAND_WORKER_RECEIPT",
                commandId,
                artifactReference,
                requestedAccessMode,
                null,
                List.of("RUNTIME_EVENT_SCOPE_RESTRICTED", "ARTIFACT_REFERENCE_SAFE_SCHEME"),
                List.of("COMMAND_WORKER_RECEIPT_NOT_FOUND_OR_OUT_OF_SCOPE"),
                List.of(
                        "确认 commandId、runId、sessionId、projectId 与 artifactReference 是否来自同一条 worker receipt。",
                        "如果 receipt 已写入但查询不到，请检查 runtime event 热窗口、项目授权范围或后续 durable artifact index。"
                )
        );
    }

    private AgentToolActionArtifactAccessAuthorizationResponse deniedArtifactUnavailable(
            String commandId,
            String artifactReference,
            String requestedAccessMode,
            AgentRuntimeEventProjectionRecord record) {
        return response(
                false,
                "DENIED_ARTIFACT_NOT_AVAILABLE",
                commandId,
                artifactReference,
                requestedAccessMode,
                record,
                List.of("RUNTIME_EVENT_SCOPE_RESTRICTED", "COMMAND_WORKER_RECEIPT_MATCHED"),
                List.of("ARTIFACT_AVAILABLE_FALSE"),
                List.of("等待 worker 完成 artifact 元数据登记后再重试，或转入任务补偿/人工复核流程。")
        );
    }

    private AgentToolActionArtifactAccessAuthorizationResponse deniedLifecycleNotEligible(
            String commandId,
            String artifactReference,
            String requestedAccessMode,
            AgentRuntimeEventProjectionRecord record) {
        return response(
                false,
                "DENIED_RECEIPT_LIFECYCLE_NOT_ELIGIBLE",
                commandId,
                artifactReference,
                requestedAccessMode,
                record,
                List.of("RUNTIME_EVENT_SCOPE_RESTRICTED", "COMMAND_WORKER_RECEIPT_MATCHED"),
                List.of("COMMAND_WORKER_RECEIPT_BEFORE_ARTIFACT_READABLE_STAGE"),
                List.of("只有执行成功、执行失败、补偿相关 receipt，或 RAG_QUERY_COMPLETED 的只读答案 artifact 才能进入 artifact 元数据访问预授权。")
        );
    }

    private AgentToolActionArtifactAccessAuthorizationResponse response(
            boolean authorized,
            String decision,
            String commandId,
            String artifactReference,
            String requestedAccessMode,
            AgentRuntimeEventProjectionRecord record,
            List<String> evidenceCodes,
            List<String> issueCodes,
            List<String> recommendedActions) {
        Map<String, Object> attributes = record == null ? Map.of() : record.attributes();
        return new AgentToolActionArtifactAccessAuthorizationResponse(
                authorized,
                decision,
                commandId,
                artifactReference,
                text(attributes, "artifactReferenceType"),
                requestedAccessMode,
                true,
                false,
                record != null,
                record == null ? null : shortFingerprint(record.identityKey()),
                record == null ? null : record.replaySequence(),
                text(attributes, "outcome"),
                record == null ? null : record.tenantId(),
                record == null ? null : record.projectId(),
                record == null ? null : record.actorId(),
                record == null ? null : record.runId(),
                record == null ? null : record.sessionId(),
                text(attributes, "toolCode"),
                List.copyOf(evidenceCodes),
                List.copyOf(issueCodes),
                List.copyOf(recommendedActions),
                PAYLOAD_POLICY
        );
    }

    private String normalizeAccessMode(String value) {
        String text = safeText(value);
        if (text == null) {
            return DEFAULT_ACCESS_MODE;
        }
        String normalized = text.toUpperCase(Locale.ROOT);
        if ("BODY_READ".equals(normalized) || "METADATA_ONLY".equals(normalized)) {
            return normalized;
        }
        return "UNSUPPORTED_REQUESTED_ACCESS_MODE";
    }

    private String text(Map<String, Object> attributes, String key) {
        Object value = attributes.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private boolean bool(Map<String, Object> attributes, String key) {
        Object value = attributes.get(key);
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private void rejectSensitiveText(String value, String fieldName) {
        if (looksSensitive(value)) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    fieldName + " 疑似包含命令、URL、路径、stdout/stderr、SQL、prompt、token 或内部 endpoint，已拒绝进入 artifact 鉴权链路");
        }
    }

    private String safeArtifactReference(String value) {
        String reference = safeText(value);
        if (reference == null) {
            return null;
        }
        String lower = reference.toLowerCase(Locale.ROOT);
        boolean allowedPrefix = lower.startsWith("agent-artifact:")
                || lower.startsWith("artifact:")
                || lower.startsWith("minio-object:")
                || lower.startsWith("agent-output:")
                || lower.startsWith("command-output:")
                || lower.startsWith("task-artifact:");
        if (!allowedPrefix
                || reference.length() > 220
                || looksSensitive(reference)
                || lower.contains("://")
                || lower.contains("..")
                || reference.contains("\\")
                || reference.contains("{")
                || reference.contains("}")
                || reference.contains("[")
                || reference.contains("]")
                || reference.contains("\n")
                || reference.contains("\r")) {
            return null;
        }
        return reference;
    }

    private boolean looksSensitive(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("select ")
                || lower.contains("insert ")
                || lower.contains("update ")
                || lower.contains("delete ")
                || lower.contains("authorization:")
                || lower.contains("bearer ")
                || lower.contains("password")
                || lower.contains("token")
                || lower.contains("prompt:")
                || lower.contains("api_key")
                || lower.contains("apikey")
                || lower.contains("commandline")
                || lower.contains("command line")
                || lower.contains("stdout")
                || lower.contains("stderr")
                || lower.contains("workingdirectory")
                || lower.contains("workspace")
                || lower.contains("http://")
                || lower.contains("https://")
                || lower.contains("jdbc:");
    }

    private String shortFingerprint(String value) {
        if (value == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(hashed).substring(0, 16);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256，无法生成 artifact receipt 指纹", exception);
        }
    }

    private String safeText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
