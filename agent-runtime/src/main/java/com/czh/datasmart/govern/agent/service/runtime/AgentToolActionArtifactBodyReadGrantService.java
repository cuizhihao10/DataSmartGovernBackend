/**
 * @Author : Cui
 * @Date: 2026/06/24 18:13
 * @Description DataSmart Govern Backend - AgentToolActionArtifactBodyReadGrantService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionArtifactAccessAuthorizationResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionArtifactAccessAuthorizeRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionArtifactBodyReadGrantRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionArtifactBodyReadGrantResponse;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 命令执行 artifact 正文读取授权决策服务。
 *
 * <p>本服务承接上一道 {@link AgentToolActionArtifactAccessAuthorizationService}
 * 的 metadata-only 归属校验结果，继续完成“正文读取前”的第二段控制面判断。
 * 这一步解决的不是“如何从 MinIO 读取字节”，而是“当前上下文是否有资格请求下游对象存储服务读取字节”。
 * 这样可以把 Agent Host 的安全边界拆成清晰的两层：</p>
 *
 * <p>第一层是运行时事实校验：artifactReference 必须能对上当前租户、项目、actor、run、session
 * 范围内的 command worker receipt，避免调用方伪造一个看似合法的引用。</p>
 *
 * <p>第二层是正文读取策略校验：调用方必须说明读取目的、读取形态和最大字节数，服务端只签发
 * “低敏决策引用”，并显式声明没有返回正文、没有签发 URL、没有发放 bearer token。后续对象存储
 * 服务仍需要依据服务端身份、ACL、DLP、恶意内容扫描、下载审计和保留期策略做最终放行。</p>
 *
 * <p>这个设计贴近现代 Agent 平台的收敛方向：模型或外部 Agent 可以提出读取工具产物的意图，
 * 但真正副作用和数据输出必须由 Host 控制面分阶段授权、可审计、可撤销、可限流。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentToolActionArtifactBodyReadGrantService {

    /**
     * 当前响应的低敏载荷承诺。
     *
     * <p>策略名故意写得很长，目的是让调用方、测试和审计系统一眼能看出：
     * 本接口只是正文读取决策，不是文件下载接口，不包含对象存储真实定位信息。</p>
     */
    public static final String PAYLOAD_POLICY =
            "BODY_READ_GRANT_DECISION_ONLY_NO_ARTIFACT_BODY_NO_STDIO_NO_SIGNED_URL_NO_BEARER_TOKEN_NO_BUCKET_KEY_NO_PROMPT_NO_SQL";

    private static final String DEFAULT_CONTENT_MODE = "OBJECT_STORE_BODY_READ_AFTER_STORE_POLICY";
    private static final int DEFAULT_MAX_READABLE_BYTES = 256 * 1024;
    private static final int HARD_MAX_READABLE_BYTES = 1024 * 1024;
    private static final Duration DEFAULT_GRANT_TTL = Duration.ofMinutes(10);

    private static final Set<String> ALLOWED_PURPOSES = Set.of(
            "TASK_RESULT_VIEW",
            "AGENT_REVIEW",
            "OPERATOR_DIAGNOSTIC",
            "AUDIT_REVIEW",
            "HUMAN_APPROVAL_REVIEW"
    );
    private static final Set<String> ALLOWED_CONTENT_MODES = Set.of(
            "OBJECT_STORE_BODY_READ_AFTER_STORE_POLICY",
            "TRUNCATED_TEXT_PREVIEW",
            "SAFE_RENDERED_PREVIEW"
    );
    private static final Set<String> ALLOWED_REQUESTER_COMPONENTS = Set.of(
            "AGENT_RUNTIME",
            "GATEWAY",
            "TASK_MANAGEMENT",
            "DATA_SYNC",
            "DATA_QUALITY",
            "OBSERVABILITY"
    );

    private final AgentToolActionArtifactAccessAuthorizationService metadataAuthorizationService;
    private final AgentToolActionArtifactBodyReadGrantRecordService grantRecordService;

    /**
     * 创建正文读取授权决策。
     *
     * @param request 正文读取目的、形态、大小上限和 artifact 低敏引用。
     * @param accessContext gateway/permission-admin 下发的当前调用方数据范围。
     * @return 低敏正文读取决策；granted=true 也不代表当前响应包含正文或可直接下载。
     */
    public AgentToolActionArtifactBodyReadGrantResponse grantBodyRead(
            AgentToolActionArtifactBodyReadGrantRequest request,
            AgentRuntimeEventQueryAccessContext accessContext) {
        validateRequest(request);

        String readPurpose = normalizeCode(request.readPurpose());
        String contentMode = normalizeOrDefault(request.requestedContentMode(), DEFAULT_CONTENT_MODE);
        String requesterComponent = normalizeOrDefault(request.requesterComponent(), "AGENT_RUNTIME");
        int maxReadableBytes = resolveMaxReadableBytes(request.maxReadableBytes());
        boolean bytesCapped = request.maxReadableBytes() != null && request.maxReadableBytes() > HARD_MAX_READABLE_BYTES;

        /*
         * 第二道门必须复用第一道门，而不是自己重新查 projection。
         * 这样 metadata 归属规则、tenant/project/actor 收口规则、artifactReference 安全 scheme
         * 只存在一份权威实现，后续补 durable artifact index 时也不会出现两套不一致的授权路径。
         */
        AgentToolActionArtifactAccessAuthorizationResponse metadataDecision =
                metadataAuthorizationService.authorize(
                        new AgentToolActionArtifactAccessAuthorizeRequest(
                                request.commandId(),
                                request.artifactReference(),
                                request.artifactReferenceType(),
                                "BODY_READ",
                                request.tenantId(),
                                request.projectId(),
                                request.actorId(),
                                request.runId(),
                                request.sessionId(),
                                request.toolCode()
                        ),
                        accessContext
                );

        if (!metadataDecision.authorized()) {
            return deniedFromMetadata(metadataDecision, readPurpose, contentMode, maxReadableBytes);
        }
        if (!ALLOWED_PURPOSES.contains(readPurpose)) {
            return deniedUnsupportedPurpose(metadataDecision, readPurpose, contentMode, maxReadableBytes);
        }
        if (!ALLOWED_CONTENT_MODES.contains(contentMode)) {
            return deniedUnsupportedContentMode(metadataDecision, readPurpose, contentMode, maxReadableBytes);
        }
        if (!ALLOWED_REQUESTER_COMPONENTS.contains(requesterComponent)) {
            return deniedUnsupportedRequester(metadataDecision, readPurpose, contentMode, maxReadableBytes);
        }

        long issuedAt = Instant.now().toEpochMilli();
        Long expiresAt = issuedAt + DEFAULT_GRANT_TTL.toMillis();
        List<String> evidenceCodes = new ArrayList<>(metadataDecision.evidenceCodes());
        evidenceCodes.add("ARTIFACT_METADATA_AUTHORIZED");
        evidenceCodes.add("READ_PURPOSE_ALLOWED");
        evidenceCodes.add("CONTENT_MODE_ALLOWED");
        evidenceCodes.add("REQUESTER_COMPONENT_ALLOWED");
        evidenceCodes.add("BODY_READ_GRANT_RECORD_STORED");
        evidenceCodes.add("BODY_NOT_RETURNED");
        evidenceCodes.add("SIGNED_URL_NOT_ISSUED");
        evidenceCodes.add("BEARER_TOKEN_NOT_ISSUED");
        evidenceCodes.add("OBJECT_STORE_FINAL_AUTHORIZATION_REQUIRED");
        if (bytesCapped) {
            evidenceCodes.add("MAX_READABLE_BYTES_CAPPED_TO_HARD_LIMIT");
        }

        AgentToolActionArtifactBodyReadGrantResponse response = response(
                true,
                "BODY_READ_GRANT_DECISION_RECORDED_OBJECT_STORE_AUTHORIZATION_REQUIRED",
                metadataDecision,
                readPurpose,
                contentMode,
                maxReadableBytes,
                grantDecisionReference(metadataDecision, readPurpose, contentMode, maxReadableBytes, expiresAt),
                expiresAt,
                evidenceCodes,
                List.of(),
                List.of(
                        "将 grantDecisionReference 作为审计串联引用传给对象存储服务，但不能把它当作 bearer token 使用。",
                        "对象存储服务必须继续校验服务端身份、对象 ACL、DLP/恶意内容扫描、下载审计、保留期和限速策略。",
                        "如果后续需要用户直接下载，应由对象存储服务在最终授权后生成短时 URL，并单独记录下载审计。"
                )
        );
        grantRecordService.recordGrantedDecision(response, issuedAt);
        return response;
    }

    /**
     * 校验正文读取决策请求本身是否低敏且结构完整。
     */
    private void validateRequest(AgentToolActionArtifactBodyReadGrantRequest request) {
        if (request == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "artifact 正文读取授权决策请求体不能为空");
        }
        if (safeText(request.commandId()) == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "artifact 正文读取授权决策必须提供 commandId");
        }
        if (safeText(request.artifactReference()) == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "artifact 正文读取授权决策必须提供 artifactReference");
        }
        if (safeText(request.readPurpose()) == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "artifact 正文读取授权决策必须提供 readPurpose，避免无目的正文读取");
        }
        if (request.maxReadableBytes() != null && request.maxReadableBytes() <= 0) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "maxReadableBytes 必须大于 0");
        }
        rejectSensitiveText(request.artifactReference(), "artifactReference");
        rejectSensitiveText(request.artifactReferenceType(), "artifactReferenceType");
        rejectSensitiveText(request.readPurpose(), "readPurpose");
        rejectSensitiveText(request.requestedContentMode(), "requestedContentMode");
        rejectSensitiveText(request.toolCode(), "toolCode");
        rejectSensitiveText(request.requesterComponent(), "requesterComponent");
    }

    private AgentToolActionArtifactBodyReadGrantResponse deniedFromMetadata(
            AgentToolActionArtifactAccessAuthorizationResponse metadataDecision,
            String readPurpose,
            String contentMode,
            int maxReadableBytes) {
        List<String> issueCodes = new ArrayList<>(metadataDecision.issueCodes());
        issueCodes.add("METADATA_ACCESS_NOT_AUTHORIZED");
        return response(
                false,
                "DENIED_METADATA_AUTHORIZATION_REQUIRED",
                metadataDecision,
                readPurpose,
                contentMode,
                maxReadableBytes,
                null,
                null,
                metadataDecision.evidenceCodes(),
                issueCodes,
                List.of(
                        "先完成 artifact metadata-only 归属校验，再进入正文读取决策。",
                        "如果 receipt 已存在但不可见，请检查 tenant/project/actor/run/session 范围或授权项目列表。"
                )
        );
    }

    private AgentToolActionArtifactBodyReadGrantResponse deniedUnsupportedPurpose(
            AgentToolActionArtifactAccessAuthorizationResponse metadataDecision,
            String readPurpose,
            String contentMode,
            int maxReadableBytes) {
        return response(
                false,
                "DENIED_UNSUPPORTED_READ_PURPOSE",
                metadataDecision,
                readPurpose,
                contentMode,
                maxReadableBytes,
                null,
                null,
                metadataDecision.evidenceCodes(),
                List.of("READ_PURPOSE_NOT_ALLOWED"),
                List.of("将 readPurpose 调整为 TASK_RESULT_VIEW、AGENT_REVIEW、OPERATOR_DIAGNOSTIC、AUDIT_REVIEW 或 HUMAN_APPROVAL_REVIEW。")
        );
    }

    private AgentToolActionArtifactBodyReadGrantResponse deniedUnsupportedContentMode(
            AgentToolActionArtifactAccessAuthorizationResponse metadataDecision,
            String readPurpose,
            String contentMode,
            int maxReadableBytes) {
        return response(
                false,
                "DENIED_UNSUPPORTED_OR_RISKY_CONTENT_MODE",
                metadataDecision,
                readPurpose,
                contentMode,
                maxReadableBytes,
                null,
                null,
                metadataDecision.evidenceCodes(),
                List.of("CONTENT_MODE_NOT_ALLOWED"),
                List.of("当前仅允许 OBJECT_STORE_BODY_READ_AFTER_STORE_POLICY、TRUNCATED_TEXT_PREVIEW 或 SAFE_RENDERED_PREVIEW。")
        );
    }

    private AgentToolActionArtifactBodyReadGrantResponse deniedUnsupportedRequester(
            AgentToolActionArtifactAccessAuthorizationResponse metadataDecision,
            String readPurpose,
            String contentMode,
            int maxReadableBytes) {
        return response(
                false,
                "DENIED_UNSUPPORTED_REQUESTER_COMPONENT",
                metadataDecision,
                readPurpose,
                contentMode,
                maxReadableBytes,
                null,
                null,
                metadataDecision.evidenceCodes(),
                List.of("REQUESTER_COMPONENT_NOT_ALLOWED"),
                List.of("仅允许 agent-runtime、gateway、task-management、data-sync、data-quality 或 observability 这类平台内部组件发起正文读取决策。")
        );
    }

    private AgentToolActionArtifactBodyReadGrantResponse response(
            boolean granted,
            String decision,
            AgentToolActionArtifactAccessAuthorizationResponse metadataDecision,
            String readPurpose,
            String contentMode,
            Integer maxReadableBytes,
            String grantDecisionReference,
            Long expiresAt,
            List<String> evidenceCodes,
            List<String> issueCodes,
            List<String> recommendedActions) {
        return new AgentToolActionArtifactBodyReadGrantResponse(
                granted,
                decision,
                metadataDecision.commandId(),
                metadataDecision.artifactReference(),
                metadataDecision.artifactReferenceType(),
                readPurpose,
                contentMode,
                maxReadableBytes,
                grantDecisionReference,
                expiresAt,
                metadataDecision.authorized(),
                false,
                false,
                false,
                true,
                metadataDecision.matchedReceiptFingerprint(),
                metadataDecision.replaySequence(),
                metadataDecision.receiptOutcome(),
                metadataDecision.tenantId(),
                metadataDecision.projectId(),
                metadataDecision.actorId(),
                metadataDecision.runId(),
                metadataDecision.sessionId(),
                metadataDecision.toolCode(),
                List.copyOf(evidenceCodes),
                List.copyOf(issueCodes),
                List.copyOf(recommendedActions),
                PAYLOAD_POLICY
        );
    }

    /**
     * 生成低敏决策引用。
     *
     * <p>该值只用于把“正文读取决策”和后续对象存储审计记录串起来，不具备 bearer token 语义。
     * 真实生产实现应把 grant 写入 durable store，并要求对象存储服务用服务端身份回查该记录。</p>
     */
    private String grantDecisionReference(AgentToolActionArtifactAccessAuthorizationResponse metadataDecision,
                                          String readPurpose,
                                          String contentMode,
                                          Integer maxReadableBytes,
                                          Long expiresAt) {
        String source = String.join("|",
                safeTextOrEmpty(metadataDecision.commandId()),
                safeTextOrEmpty(metadataDecision.artifactReference()),
                safeTextOrEmpty(metadataDecision.matchedReceiptFingerprint()),
                safeTextOrEmpty(metadataDecision.runId()),
                safeTextOrEmpty(metadataDecision.sessionId()),
                readPurpose,
                contentMode,
                String.valueOf(maxReadableBytes),
                String.valueOf(expiresAt)
        );
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(source.getBytes(StandardCharsets.UTF_8));
            return "artifact-body-grant-decision:sha256:" + HexFormat.of().formatHex(hashed).substring(0, 24);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256，无法生成 artifact 正文读取决策引用", exception);
        }
    }

    private int resolveMaxReadableBytes(Integer requested) {
        if (requested == null) {
            return DEFAULT_MAX_READABLE_BYTES;
        }
        return Math.min(requested, HARD_MAX_READABLE_BYTES);
    }

    private String normalizeOrDefault(String value, String defaultValue) {
        String normalized = normalizeCode(value);
        return normalized == null ? defaultValue : normalized;
    }

    private String normalizeCode(String value) {
        String text = safeText(value);
        if (text == null) {
            return null;
        }
        return text.toUpperCase(Locale.ROOT).replace('-', '_').replace('.', '_');
    }

    private void rejectSensitiveText(String value, String fieldName) {
        if (looksSensitive(value)) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    fieldName + " 疑似包含命令、URL、路径、stdout/stderr、SQL、prompt、token、bucket/key 或内部 endpoint，已拒绝进入 artifact 正文读取授权链路");
        }
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
                || lower.contains("bucket")
                || lower.contains("object-key")
                || lower.contains("object_key")
                || lower.contains("http://")
                || lower.contains("https://")
                || lower.contains("jdbc:");
    }

    private String safeText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String safeTextOrEmpty(String value) {
        String text = safeText(value);
        return text == null ? "" : text;
    }
}
