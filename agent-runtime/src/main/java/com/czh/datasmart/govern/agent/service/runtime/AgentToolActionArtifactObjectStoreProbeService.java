/**
 * @Author : Cui
 * @Date: 2026/06/26 23:15
 * @Description DataSmart Govern Backend - AgentToolActionArtifactObjectStoreProbeService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionArtifactBodyReadGrantRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionArtifactBodyReadGrantResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionArtifactObjectStoreProbeRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionArtifactObjectStoreProbeResponse;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * artifact 对象存储探针服务。
 *
 * <p>这个服务把 artifact 读取链路从“授权合同已定义”推进到“对象存储 adapter 可以被安全接入”。
 * 它刻意只做服务端探针，不返回正文。业务意义是：平台可以验证某个 artifactReference 背后对象是否存在、
 * adapter 是否启用、对象大小/类型是否可见、sample 是否能被读取并计算指纹；但用户、模型、前端或外部 Agent
 * 仍然不能绕过 final-check 直接拿到 artifact body。</p>
 *
 * <p>完整流程如下：</p>
 *
 * <p>1. 校验请求低敏字段和上一阶段 grant 引用形态；</p>
 * <p>2. 重新调用 {@link AgentToolActionArtifactBodyReadGrantService}，确认当前时刻仍有 body-read grant；</p>
 * <p>3. 按 Host 硬上限裁剪探针 sample 字节数，避免大对象或日志炸弹冲击 Java API；</p>
 * <p>4. 通过 {@link AgentToolActionArtifactObjectStoreClient} 访问可替换对象存储 adapter；</p>
 * <p>5. 对 adapter 返回的 sample 只计算短指纹和统计，不把 sample 字节写入响应、事件、日志或缓存。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentToolActionArtifactObjectStoreProbeService {

    /**
     * 当前响应的低敏载荷承诺。
     */
    public static final String PAYLOAD_POLICY =
            "OBJECT_STORE_PROBE_METADATA_ONLY_NO_ARTIFACT_BODY_NO_SAMPLE_BYTES_NO_SIGNED_URL_NO_BEARER_TOKEN_NO_BUCKET_KEY_NO_PROMPT_NO_SQL";

    private static final int DEFAULT_PROBE_BYTES = 16 * 1024;
    private static final int HARD_PROBE_BYTES = 64 * 1024;
    private static final Pattern GRANT_DECISION_REFERENCE_PATTERN =
            Pattern.compile("^artifact-body-grant-decision:sha256:[0-9a-f]{24}$");
    private static final Set<String> ALLOWED_REFERENCE_PREFIXES = Set.of(
            "agent-artifact:",
            "artifact:",
            "artifact-ref:",
            "command-output:",
            "task-artifact:",
            "minio-object:"
    );

    private final AgentToolActionArtifactBodyReadGrantService bodyReadGrantService;
    private final AgentToolActionArtifactObjectStoreClient objectStoreClient;

    /**
     * 执行对象存储探针。
     *
     * @param request artifact 低敏引用、上一阶段 grant 引用和探针字节预算。
     * @param accessContext gateway/permission-admin 解析后的当前访问范围，不能被请求体扩大。
     * @return 对象可用性、sample 指纹和低敏审计证据；不会返回正文或下载凭据。
     */
    public AgentToolActionArtifactObjectStoreProbeResponse probe(
            AgentToolActionArtifactObjectStoreProbeRequest request,
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

        int probeLimitBytes = resolveProbeLimitBytes(request.requestedProbeBytes(), grantDecision.maxReadableBytes());
        AgentToolActionArtifactObjectStoreProbeSample adapterSample = objectStoreClient.probe(
                new AgentToolActionArtifactObjectStoreProbeCommand(
                        grantDecision.commandId(),
                        grantDecision.artifactReference(),
                        grantDecision.artifactReferenceType(),
                        grantDecision.readPurpose(),
                        grantDecision.requestedContentMode(),
                        probeLimitBytes,
                        grantDecision.tenantId(),
                        grantDecision.projectId(),
                        grantDecision.actorId(),
                        grantDecision.runId(),
                        grantDecision.sessionId(),
                        grantDecision.toolCode()
                )
        );

        return responseFromAdapter(request, grantDecision, normalizeSample(adapterSample), probeLimitBytes);
    }

    /**
     * 校验请求体结构与低敏边界。
     */
    private void validateRequest(AgentToolActionArtifactObjectStoreProbeRequest request) {
        if (request == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "artifact 对象存储探针请求体不能为空");
        }
        if (safeText(request.commandId()) == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "artifact 对象存储探针必须提供 commandId");
        }
        String artifactReference = safeText(request.artifactReference());
        if (artifactReference == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "artifact 对象存储探针必须提供 artifactReference");
        }
        if (!hasAllowedReferencePrefix(artifactReference)) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "artifactReference 必须使用 agent-artifact、artifact、artifact-ref、command-output、task-artifact 或 minio-object 低敏引用前缀");
        }
        String grantReference = safeText(request.previousGrantDecisionReference());
        if (grantReference == null || !GRANT_DECISION_REFERENCE_PATTERN.matcher(grantReference).matches()) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "previousGrantDecisionReference 必须是 body-read-grants 返回的低敏决策引用形态");
        }
        if (request.requestedProbeBytes() != null && request.requestedProbeBytes() <= 0) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "requestedProbeBytes 必须大于 0");
        }
        rejectSensitiveControlText(request.artifactReference(), "artifactReference");
        rejectSensitiveControlText(request.artifactReferenceType(), "artifactReferenceType");
        rejectSensitiveControlText(request.readPurpose(), "readPurpose");
        rejectSensitiveControlText(request.requestedContentMode(), "requestedContentMode");
        rejectSensitiveControlText(request.toolCode(), "toolCode");
        rejectSensitiveControlText(request.requesterComponent(), "requesterComponent");
    }

    /**
     * grant 复核失败时不能调用对象存储 adapter，也不能泄露对象是否存在。
     */
    private AgentToolActionArtifactObjectStoreProbeResponse deniedFromGrant(
            AgentToolActionArtifactObjectStoreProbeRequest request,
            AgentToolActionArtifactBodyReadGrantResponse grantDecision) {
        List<String> issueCodes = new ArrayList<>(safeList(grantDecision.issueCodes()));
        issueCodes.add("BODY_READ_GRANT_RECHECK_NOT_GRANTED");
        return response(
                false,
                "DENIED_BODY_READ_GRANT_REQUIRED",
                request,
                grantDecision,
                0,
                false,
                false,
                null,
                null,
                0,
                false,
                null,
                null,
                safeList(grantDecision.evidenceCodes()),
                issueCodes,
                List.of(
                        "先完成 artifact metadata-only 归属校验和 body-read grant，再进入对象存储探针。",
                        "如果 grant 曾经成功但当前失败，请检查租户、项目、actor、run/session、读取目的或内容模式是否发生变化。"
                )
        );
    }

    /**
     * 把 adapter 内部 sample 转换成低敏 HTTP 响应。
     */
    private AgentToolActionArtifactObjectStoreProbeResponse responseFromAdapter(
            AgentToolActionArtifactObjectStoreProbeRequest request,
            AgentToolActionArtifactBodyReadGrantResponse grantDecision,
            AgentToolActionArtifactObjectStoreProbeSample sample,
            int probeLimitBytes) {
        ClippedSample clippedSample = clipSample(sample.sampleBytes(), probeLimitBytes, sample.sampleTruncated());
        List<String> evidenceCodes = new ArrayList<>(safeList(grantDecision.evidenceCodes()));
        evidenceCodes.add("BODY_READ_GRANT_RECHECKED");
        evidenceCodes.add("GRANT_DECISION_REFERENCE_SHAPE_VERIFIED");
        evidenceCodes.add("OBJECT_STORE_ADAPTER_BOUNDARY_USED");
        evidenceCodes.add("ARTIFACT_BODY_NOT_RETURNED");
        evidenceCodes.add("SAMPLE_BYTES_NOT_RETURNED");
        evidenceCodes.addAll(safeList(sample.evidenceCodes()));
        if (sample.probeExecuted()) {
            evidenceCodes.add("OBJECT_STORE_PROBE_EXECUTED");
        }
        if (clippedSample.hostClipped()) {
            evidenceCodes.add("OBJECT_STORE_SAMPLE_CLIPPED_BY_HOST_POLICY");
        }

        List<String> issueCodes = new ArrayList<>(safeList(sample.issueCodes()));
        if (!sample.probeExecuted()) {
            issueCodes.add("OBJECT_STORE_PROBE_NOT_EXECUTED");
        }
        if (!sample.objectAvailable()) {
            issueCodes.add("OBJECT_STORE_OBJECT_NOT_AVAILABLE");
        }
        if (clippedSample.hostClipped()) {
            issueCodes.add("OBJECT_STORE_SAMPLE_EXCEEDED_HOST_LIMIT");
        }

        boolean allowed = sample.probeExecuted() && sample.objectAvailable();
        String decision = allowed
                ? "OBJECT_STORE_PROBE_VERIFIED_NO_BODY_RETURNED"
                : "OBJECT_STORE_PROBE_UNAVAILABLE_NO_BODY_RETURNED";

        return response(
                allowed,
                decision,
                request,
                grantDecision,
                probeLimitBytes,
                sample.probeExecuted(),
                sample.objectAvailable(),
                normalizeContentType(sample.contentType()),
                sample.contentLengthBytes(),
                clippedSample.bytes().length,
                clippedSample.truncated(),
                sampleFingerprint(clippedSample.bytes()),
                safeText(sample.objectVersionFingerprint()),
                evidenceCodes,
                issueCodes,
                safeList(sample.recommendedActions()).isEmpty()
                        ? defaultRecommendedActions(allowed)
                        : safeList(sample.recommendedActions())
        );
    }

    private AgentToolActionArtifactObjectStoreProbeResponse response(
            boolean probeAllowed,
            String decision,
            AgentToolActionArtifactObjectStoreProbeRequest request,
            AgentToolActionArtifactBodyReadGrantResponse grantDecision,
            Integer probeLimitBytes,
            boolean objectStoreProbeExecuted,
            boolean objectAvailable,
            String contentType,
            Long contentLengthBytes,
            Integer sampledBytes,
            boolean sampleTruncated,
            String sampleSha256Fingerprint,
            String objectVersionFingerprint,
            List<String> evidenceCodes,
            List<String> issueCodes,
            List<String> recommendedActions) {
        return new AgentToolActionArtifactObjectStoreProbeResponse(
                probeAllowed,
                decision,
                grantDecision.commandId(),
                grantDecision.artifactReference(),
                grantDecision.artifactReferenceType(),
                grantDecision.readPurpose(),
                grantDecision.requestedContentMode(),
                grantDecision.maxReadableBytes(),
                probeLimitBytes,
                objectStoreProbeExecuted,
                objectAvailable,
                contentType,
                contentLengthBytes,
                sampledBytes,
                sampleTruncated,
                sampleSha256Fingerprint,
                objectVersionFingerprint,
                false,
                false,
                false,
                safeText(request.previousGrantDecisionReference()),
                grantDecision.grantDecisionReference(),
                grantDecision.grantExpiresAtEpochMs(),
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
     * 计算 Host 探针字节上限。
     */
    private int resolveProbeLimitBytes(Integer requestedProbeBytes, Integer grantMaxReadableBytes) {
        int requested = requestedProbeBytes == null ? DEFAULT_PROBE_BYTES : requestedProbeBytes;
        int grantLimit = grantMaxReadableBytes == null ? DEFAULT_PROBE_BYTES : grantMaxReadableBytes;
        return Math.min(Math.min(requested, grantLimit), HARD_PROBE_BYTES);
    }

    private AgentToolActionArtifactObjectStoreProbeSample normalizeSample(
            AgentToolActionArtifactObjectStoreProbeSample sample) {
        if (sample == null) {
            return new AgentToolActionArtifactObjectStoreProbeSample(
                    false,
                    false,
                    null,
                    null,
                    new byte[0],
                    false,
                    null,
                    List.of(),
                    List.of("OBJECT_STORE_ADAPTER_RETURNED_NULL"),
                    List.of("检查对象存储 adapter 实现，probe 方法必须返回结构化结果而不是 null。")
            );
        }
        return sample;
    }

    /**
     * 即使 adapter 误返回超出上限的 sample，Host 也必须二次裁剪。
     */
    private ClippedSample clipSample(byte[] sampleBytes, int probeLimitBytes, boolean adapterTruncated) {
        byte[] bytes = sampleBytes == null ? new byte[0] : sampleBytes;
        boolean hostClipped = bytes.length > probeLimitBytes;
        byte[] clipped = hostClipped ? Arrays.copyOf(bytes, probeLimitBytes) : bytes;
        return new ClippedSample(clipped, adapterTruncated || hostClipped, hostClipped);
    }

    private String sampleFingerprint(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(bytes);
            return "sample-sha256:" + HexFormat.of().formatHex(hashed).substring(0, 24);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256，无法生成对象存储 sample 指纹", exception);
        }
    }

    private List<String> defaultRecommendedActions(boolean allowed) {
        if (allowed) {
            return List.of(
                    "对象存储探针已确认 artifact 可服务端读取；如果要展示短预览，仍必须继续调用 body-read-final-checks。",
                    "不要把 sample 指纹当作正文缓存，真实下载仍需对象存储 ACL、DLP、下载审计、限速和保留期策略。"
            );
        }
        return List.of(
                "检查真实对象存储 adapter 是否启用、artifactReference 是否已物化、对象是否过期或被清理。",
                "在 durable grant store 和 MinIO/S3-compatible adapter 完成前，不要开放完整 artifact 下载。"
        );
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private boolean hasAllowedReferencePrefix(String artifactReference) {
        String lower = artifactReference.toLowerCase(Locale.ROOT);
        return ALLOWED_REFERENCE_PREFIXES.stream().anyMatch(lower::startsWith);
    }

    private void rejectSensitiveControlText(String value, String fieldName) {
        if (looksSensitive(value)) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    fieldName + " 疑似包含 URL、路径、stdout/stderr、SQL、prompt、凭证、bucket/key 或内部 endpoint，已拒绝进入 artifact 对象存储探针链路");
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
                || lower.contains("../")
                || lower.contains("..\\")
                || lower.matches("^[a-z]:\\\\.*")
                || lower.startsWith("\\\\");
    }

    private String normalizeContentType(String value) {
        String text = safeText(value);
        return text == null ? null : text.toLowerCase(Locale.ROOT);
    }

    private String safeText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Host 二次裁剪后的 sample 摘要，record 内仍只在内存中持有 bytes，不会进入 HTTP DTO。
     */
    private record ClippedSample(byte[] bytes, boolean truncated, boolean hostClipped) {
    }
}
