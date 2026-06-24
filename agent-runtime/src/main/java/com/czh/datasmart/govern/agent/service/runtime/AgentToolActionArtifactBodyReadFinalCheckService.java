/**
 * @Author : Cui
 * @Date: 2026/06/24 20:43
 * @Description DataSmart Govern Backend - AgentToolActionArtifactBodyReadFinalCheckService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionArtifactBodyReadFinalCheckRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionArtifactBodyReadFinalCheckResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionArtifactBodyReadGrantRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionArtifactBodyReadGrantResponse;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * artifact 正文读取最终回查与安全预览裁剪服务。
 *
 * <p>这个服务是 command durable action 小闭环里“读 artifact 正文前”的最后一层 Host 控制面。
 * 它不连接 MinIO，不打开对象，不读取真实 stdout/stderr，也不生成下载 URL。当前阶段只做三件事：</p>
 *
 * <ol>
 *     <li>复用 {@link AgentToolActionArtifactBodyReadGrantService} 重新校验 metadata 归属、读取目的、读取形态和调用组件；</li>
 *     <li>校验调用方提交的上一阶段 grant 决策引用是否具备低敏审计引用形态，避免完全跳过 grant 步骤；</li>
 *     <li>对已经由下游对象存储服务脱敏后的候选预览做敏感标记拦截和 UTF-8 字节级裁剪。</li>
 * </ol>
 *
 * <p>为什么不是直接读取对象？因为商业化 Agent Host 里，artifact 可能来自命令执行、数据同步、质量报告、
 * 审批附件或诊断采样。对象存储服务必须独立承担 ACL、DLP、恶意内容扫描、保留期、下载审计和限速职责。
 * Java Agent Runtime 在这里承担的是“控制面最终回查”，即确认这次读取仍然符合当前租户、项目、actor、run/session
 * 与读取目的的约束，并确保返回给上游的内容最多只是短预览。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentToolActionArtifactBodyReadFinalCheckService {

    /**
     * 本响应的低敏载荷承诺。
     *
     * <p>策略名刻意强调“不返回完整正文、不签 URL、不发 token、不暴露 bucket/key、不携带 prompt 或 SQL”。
     * 这样在接口文档、审计记录和自动化测试里都能直接看出本服务的边界。</p>
     */
    public static final String PAYLOAD_POLICY =
            "SAFE_PREVIEW_FINAL_CHECK_ONLY_NO_FULL_BODY_NO_RAW_OUTPUT_NO_SIGNED_URL_NO_BEARER_TOKEN_NO_BUCKET_KEY_NO_PROMPT_NO_SQL";

    private static final int DEFAULT_PREVIEW_BYTES = 32 * 1024;
    private static final int HARD_PREVIEW_BYTES = 64 * 1024;
    private static final Pattern GRANT_DECISION_REFERENCE_PATTERN =
            Pattern.compile("^artifact-body-grant-decision:sha256:[0-9a-f]{24}$");
    private static final Set<String> PREVIEW_CONTENT_MODES = Set.of(
            "TRUNCATED_TEXT_PREVIEW",
            "SAFE_RENDERED_PREVIEW"
    );

    private final AgentToolActionArtifactBodyReadGrantService bodyReadGrantService;

    /**
     * 执行 artifact 正文读取最终回查。
     *
     * @param request 对象存储服务或 artifact 服务提交的最终回查请求，包含低敏引用、grant 引用和候选安全预览。
     * @param accessContext gateway/permission-admin 解析后的当前访问范围，不能被请求体扩大。
     * @return final-check 结果；allowed=true 也只代表可以返回安全预览或继续受控读取流程，不代表已经返回完整正文。
     */
    public AgentToolActionArtifactBodyReadFinalCheckResponse finalCheck(
            AgentToolActionArtifactBodyReadFinalCheckRequest request,
            AgentRuntimeEventQueryAccessContext accessContext) {
        validateRequest(request);

        AgentToolActionArtifactBodyReadGrantResponse grantDecision = bodyReadGrantService.grantBodyRead(
                new AgentToolActionArtifactBodyReadGrantRequest(
                        request.commandId(),
                        request.artifactReference(),
                        request.artifactReferenceType(),
                        request.readPurpose(),
                        request.requestedContentMode(),
                        request.maxReadableBytes(),
                        request.tenantId(),
                        request.projectId(),
                        request.actorId(),
                        request.runId(),
                        request.sessionId(),
                        request.toolCode(),
                        request.requesterComponent()
                ),
                accessContext
        );

        if (!grantDecision.granted()) {
            return deniedFromGrant(request, grantDecision);
        }

        String contentMode = normalizeCode(grantDecision.requestedContentMode());
        boolean previewMode = PREVIEW_CONTENT_MODES.contains(contentMode);
        int previewLimitBytes = resolvePreviewLimitBytes(request.requestedMaxPreviewBytes(), grantDecision.maxReadableBytes());

        if (!previewMode) {
            return allowedWithoutPreview(request, grantDecision, previewLimitBytes);
        }

        String previewCandidate = safeText(request.sanitizedPreviewText());
        if (previewCandidate == null) {
            return deniedMissingPreview(request, grantDecision, previewLimitBytes);
        }
        rejectSensitivePreview(previewCandidate);

        PreviewClipResult clipResult = clipUtf8(previewCandidate, previewLimitBytes);
        List<String> evidenceCodes = new ArrayList<>(grantDecision.evidenceCodes());
        evidenceCodes.add("BODY_READ_GRANT_RECHECKED");
        evidenceCodes.add("GRANT_DECISION_REFERENCE_SHAPE_VERIFIED");
        evidenceCodes.add("OBJECT_STORE_FINAL_CHECK_COMPLETED");
        evidenceCodes.add("SAFE_PREVIEW_SANITIZED_BY_CALLER");
        evidenceCodes.add("SAFE_PREVIEW_CLIPPED_BY_HOST_POLICY");
        evidenceCodes.add("FULL_BODY_NOT_RETURNED");
        evidenceCodes.add("SIGNED_URL_NOT_ISSUED");
        evidenceCodes.add("BEARER_TOKEN_NOT_ISSUED");
        if (clipResult.truncated()) {
            evidenceCodes.add("SAFE_PREVIEW_TRUNCATED_TO_BYTE_LIMIT");
        }
        if (request.candidateContentLengthBytes() != null
                && grantDecision.maxReadableBytes() != null
                && request.candidateContentLengthBytes() > grantDecision.maxReadableBytes()) {
            evidenceCodes.add("CANDIDATE_LENGTH_EXCEEDS_GRANT_MAX_PREVIEW_ONLY");
        }

        return response(
                true,
                "ALLOWED_SAFE_PREVIEW_AFTER_FINAL_CHECK",
                request,
                grantDecision,
                previewLimitBytes,
                clipResult.bytes(),
                clipResult.truncated(),
                true,
                clipResult.text(),
                true,
                evidenceCodes,
                List.of(),
                List.of(
                        "将 safePreviewText 作为短预览展示，不要把它当作完整 artifact 正文缓存或重新注入模型上下文。",
                        "如果需要完整下载，应由对象存储服务在 ACL、DLP、恶意内容扫描、下载审计和限速全部通过后单独处理。",
                        "后续生产化应把 grantDecisionReference 写入 durable grant store，并由对象存储服务按服务端身份回查。"
                )
        );
    }

    /**
     * 校验请求体的结构完整性和低敏边界。
     */
    private void validateRequest(AgentToolActionArtifactBodyReadFinalCheckRequest request) {
        if (request == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "artifact 正文读取最终回查请求体不能为空");
        }
        if (safeText(request.commandId()) == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "artifact 正文读取最终回查必须提供 commandId");
        }
        if (safeText(request.artifactReference()) == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "artifact 正文读取最终回查必须提供 artifactReference");
        }
        String grantReference = safeText(request.previousGrantDecisionReference());
        if (grantReference == null || !GRANT_DECISION_REFERENCE_PATTERN.matcher(grantReference).matches()) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "previousGrantDecisionReference 必须是 body-read-grants 返回的低敏决策引用形态");
        }
        if (request.requestedMaxPreviewBytes() != null && request.requestedMaxPreviewBytes() <= 0) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "requestedMaxPreviewBytes 必须大于 0");
        }
        if (request.candidateContentLengthBytes() != null && request.candidateContentLengthBytes() < 0) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "candidateContentLengthBytes 不能小于 0");
        }
        rejectSensitiveControlText(request.artifactReference(), "artifactReference");
        rejectSensitiveControlText(request.artifactReferenceType(), "artifactReferenceType");
        rejectSensitiveControlText(request.readPurpose(), "readPurpose");
        rejectSensitiveControlText(request.requestedContentMode(), "requestedContentMode");
        rejectSensitiveControlText(request.candidateContentType(), "candidateContentType");
        rejectSensitiveControlText(request.toolCode(), "toolCode");
        rejectSensitiveControlText(request.requesterComponent(), "requesterComponent");
    }

    /**
     * grant 服务拒绝时，final-check 不能再继续返回任何预览。
     */
    private AgentToolActionArtifactBodyReadFinalCheckResponse deniedFromGrant(
            AgentToolActionArtifactBodyReadFinalCheckRequest request,
            AgentToolActionArtifactBodyReadGrantResponse grantDecision) {
        List<String> issueCodes = new ArrayList<>(grantDecision.issueCodes());
        issueCodes.add("BODY_READ_GRANT_RECHECK_NOT_GRANTED");
        return response(
                false,
                "DENIED_BODY_READ_GRANT_REQUIRED",
                request,
                grantDecision,
                0,
                0,
                false,
                false,
                null,
                true,
                grantDecision.evidenceCodes(),
                issueCodes,
                List.of(
                        "先完成 artifact metadata-only 归属校验和 body-read grant，再进入最终回查。",
                        "如果 grant 曾经成功但当前失败，请检查租户、项目、actor、run/session、读取目的或内容模式是否发生变化。"
                )
        );
    }

    /**
     * 非预览模式只返回“最终回查通过”的低敏决策，不返回安全预览文本。
     */
    private AgentToolActionArtifactBodyReadFinalCheckResponse allowedWithoutPreview(
            AgentToolActionArtifactBodyReadFinalCheckRequest request,
            AgentToolActionArtifactBodyReadGrantResponse grantDecision,
            int previewLimitBytes) {
        List<String> evidenceCodes = new ArrayList<>(grantDecision.evidenceCodes());
        evidenceCodes.add("BODY_READ_GRANT_RECHECKED");
        evidenceCodes.add("GRANT_DECISION_REFERENCE_SHAPE_VERIFIED");
        evidenceCodes.add("OBJECT_STORE_FINAL_CHECK_COMPLETED");
        evidenceCodes.add("CONTENT_MODE_DOES_NOT_RETURN_PREVIEW");
        evidenceCodes.add("FULL_BODY_NOT_RETURNED");
        evidenceCodes.add("SIGNED_URL_NOT_ISSUED");
        evidenceCodes.add("BEARER_TOKEN_NOT_ISSUED");
        return response(
                true,
                "ALLOWED_FINAL_CHECK_WITHOUT_PREVIEW",
                request,
                grantDecision,
                previewLimitBytes,
                0,
                false,
                false,
                null,
                true,
                evidenceCodes,
                List.of(),
                List.of(
                        "当前 requestedContentMode 不返回预览文本；如果需要展示短预览，请改用 TRUNCATED_TEXT_PREVIEW 或 SAFE_RENDERED_PREVIEW。",
                        "完整对象读取仍应由对象存储服务在 ACL、DLP、下载审计和限速通过后执行。"
                )
        );
    }

    /**
     * 预览模式下必须提供已脱敏候选文本，否则返回可解释的业务拒绝，而不是静默返回空预览。
     */
    private AgentToolActionArtifactBodyReadFinalCheckResponse deniedMissingPreview(
            AgentToolActionArtifactBodyReadFinalCheckRequest request,
            AgentToolActionArtifactBodyReadGrantResponse grantDecision,
            int previewLimitBytes) {
        List<String> issueCodes = new ArrayList<>(grantDecision.issueCodes());
        issueCodes.add("SANITIZED_PREVIEW_REQUIRED");
        return response(
                false,
                "DENIED_SANITIZED_PREVIEW_REQUIRED",
                request,
                grantDecision,
                previewLimitBytes,
                0,
                false,
                false,
                null,
                true,
                grantDecision.evidenceCodes(),
                issueCodes,
                List.of("预览模式必须由对象存储服务先完成基础脱敏，并传入 sanitizedPreviewText。")
        );
    }

    private AgentToolActionArtifactBodyReadFinalCheckResponse response(
            boolean allowed,
            String decision,
            AgentToolActionArtifactBodyReadFinalCheckRequest request,
            AgentToolActionArtifactBodyReadGrantResponse grantDecision,
            Integer previewLimitBytes,
            Integer previewBytes,
            boolean previewTruncated,
            boolean safePreviewReturned,
            String safePreviewText,
            boolean objectStoreReadVerified,
            List<String> evidenceCodes,
            List<String> issueCodes,
            List<String> recommendedActions) {
        return new AgentToolActionArtifactBodyReadFinalCheckResponse(
                allowed,
                decision,
                grantDecision.commandId(),
                grantDecision.artifactReference(),
                grantDecision.artifactReferenceType(),
                grantDecision.readPurpose(),
                grantDecision.requestedContentMode(),
                grantDecision.maxReadableBytes(),
                previewLimitBytes,
                previewBytes,
                previewTruncated,
                safePreviewReturned,
                safePreviewText,
                false,
                objectStoreReadVerified,
                false,
                false,
                safeText(request.previousGrantDecisionReference()),
                grantDecision.grantDecisionReference(),
                grantDecision.grantExpiresAtEpochMs(),
                normalizeContentType(request.candidateContentType()),
                request.candidateContentLengthBytes(),
                grantDecision.matchedReceiptFingerprint(),
                grantDecision.replaySequence(),
                grantDecision.receiptOutcome(),
                grantDecision.tenantId(),
                grantDecision.projectId(),
                grantDecision.actorId(),
                grantDecision.runId(),
                grantDecision.sessionId(),
                grantDecision.toolCode(),
                List.copyOf(evidenceCodes),
                List.copyOf(issueCodes),
                List.copyOf(recommendedActions),
                PAYLOAD_POLICY
        );
    }

    /**
     * 解析预览字节上限。
     *
     * <p>最终上限取三者最小值：调用方请求值、body-read grant 允许值、Host 内置硬上限。
     * 这样即使下游对象存储服务误传了很大的预览大小，也不会让 Java API 响应膨胀。</p>
     */
    private int resolvePreviewLimitBytes(Integer requestedMaxPreviewBytes, Integer grantMaxReadableBytes) {
        int requested = requestedMaxPreviewBytes == null ? DEFAULT_PREVIEW_BYTES : requestedMaxPreviewBytes;
        int grantLimit = grantMaxReadableBytes == null ? DEFAULT_PREVIEW_BYTES : grantMaxReadableBytes;
        return Math.min(Math.min(requested, grantLimit), HARD_PREVIEW_BYTES);
    }

    /**
     * 按 UTF-8 字节数裁剪，避免中文、emoji 或多字节字符在中间被截断成非法字符串。
     */
    private PreviewClipResult clipUtf8(String text, int maxBytes) {
        byte[] allBytes = text.getBytes(StandardCharsets.UTF_8);
        if (allBytes.length <= maxBytes) {
            return new PreviewClipResult(text, allBytes.length, false);
        }
        StringBuilder builder = new StringBuilder();
        int usedBytes = 0;
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            String current = new String(Character.toChars(codePoint));
            int currentBytes = current.getBytes(StandardCharsets.UTF_8).length;
            if (usedBytes + currentBytes > maxBytes) {
                break;
            }
            builder.append(current);
            usedBytes += currentBytes;
            offset += Character.charCount(codePoint);
        }
        return new PreviewClipResult(builder.toString(), usedBytes, true);
    }

    private void rejectSensitiveControlText(String value, String fieldName) {
        if (looksSensitive(value)) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    fieldName + " 疑似包含 URL、路径、输出通道、SQL、prompt、凭证、bucket/key 或内部 endpoint，已拒绝进入 artifact 最终回查链路");
        }
    }

    private void rejectSensitivePreview(String value) {
        if (looksSensitive(value)) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "sanitizedPreviewText 仍疑似包含敏感正文、URL、SQL、prompt、凭证、对象存储定位或原始输出通道标记，已拒绝返回安全预览");
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
                || lower.contains("secret")
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
                || lower.contains("jdbc:")
                || lower.contains("-----begin");
    }

    private String normalizeCode(String value) {
        String text = safeText(value);
        if (text == null) {
            return null;
        }
        return text.toUpperCase(Locale.ROOT).replace('-', '_').replace('.', '_');
    }

    private String normalizeContentType(String value) {
        String text = safeText(value);
        return text == null ? null : text.toLowerCase(Locale.ROOT);
    }

    private String safeText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * 字节裁剪结果，使用小 record 让主流程不用同时维护 text、bytes、truncated 三个易错变量。
     */
    private record PreviewClipResult(String text, int bytes, boolean truncated) {
    }
}
