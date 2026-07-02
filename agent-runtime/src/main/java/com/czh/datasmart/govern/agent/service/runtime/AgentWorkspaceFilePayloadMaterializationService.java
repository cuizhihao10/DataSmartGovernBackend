/**
 * @Author : Cui
 * @Date: 2026/06/29 00:00
 * @Description DataSmart Govern Backend - AgentWorkspaceFilePayloadMaterializationService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.czh.datasmart.govern.agent.service.runtime.WorkspaceFilePayloadDigestSupport.sha256;
import static com.czh.datasmart.govern.agent.service.runtime.WorkspaceFilePayloadValueSupport.hasText;
import static com.czh.datasmart.govern.agent.service.runtime.WorkspaceFilePayloadValueSupport.safeText;

/**
 * Workspace 文件工具 payload 物化服务。
 *
 * <p>该服务处在“Agent 工具规划”和“durable command / worker 真正执行”之间，解决的是商业化 Agent Host
 * 的一个关键闭环问题：模型或 Python Runtime 可以规划 `workspace.file.read/write`，但真实文件路径、写入正文、
 * workspace root、工具 arguments 等敏感内容不能直接出现在 runtime event、projection、审批页摘要、日志或 outbox
 * 可见 payload 中。因此这里把真实参数物化到服务端 `agent-payload:` store，对外只返回摘要、大小、策略和问题码。</p>
 *
 * <p>为什么不直接让 writer 从 Python 事件里拿参数执行：</p>
 * <p>1. 事件需要可观测和可回放，里面如果带文件正文或路径，会变成长期敏感数据泄露面；</p>
 * <p>2. durable outbox 可能被重试、补偿、人工查看，不能保存原始 prompt、SQL、文件正文或密钥；</p>
 * <p>3. worker 执行前必须重新校验 run、tenant、project、actor、tool、contract、TTL 和策略，而不是相信一次模型输出；</p>
 * <p>4. 后续接入 MinIO/KMS/MySQL 时，`agent-payload:` 可以作为稳定引用，不需要重写上游工具规划和审批链路。</p>
 *
 * <p>本服务只做“参数物化和安全校验”，不会真正读写文件。真实文件读写仍应由后续 workspace worker 在获得
 * payloadReference、artifact grant、审批事实、DLP/恶意内容扫描结果和 worker lease 后执行。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentWorkspaceFilePayloadMaterializationService {

    /**
     * 对外响应使用的低敏策略名。
     *
     * <p>该策略名称刻意强调“引用与摘要优先”，用于提醒调用方：响应可以进入 API、事件、timeline 和审批页，
     * 但不能期待从响应里拿到文件路径或正文。真正参数只能由受控 worker 在服务端内部按引用读取。</p>
     */
    public static final String PAYLOAD_POLICY = "WORKSPACE_FILE_PAYLOAD_REFERENCE_ONLY_PATH_AND_CONTENT_NOT_EXPOSED";

    private static final String READ_TOOL = "workspace.file.read";
    private static final String WRITE_TOOL = "workspace.file.write";
    private static final int DEFAULT_MAX_INLINE_CONTENT_BYTES = 256 * 1024;
    private static final int MAX_RELATIVE_PATH_LENGTH = 240;
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(30);

    private static final Set<String> DENIED_SEGMENTS = Set.of(
            ".git", ".ssh", ".aws", ".azure", ".gcp", ".kube", ".m2", ".gradle", "node_modules"
    );
    private static final Set<String> DENIED_FILE_NAMES = Set.of(
            ".env", "id_rsa", "id_dsa", "id_ecdsa", "id_ed25519", "known_hosts",
            "credentials", "credential", "secrets", "secret", "kubeconfig"
    );
    private static final List<String> DENIED_SUFFIXES = List.of(
            ".pem", ".key", ".p12", ".pfx", ".jks", ".keystore", ".sqlite", ".db"
    );
    private static final List<String> INLINE_CONTENT_RISK_MARKERS = List.of(
            "password=", "passwd=", "api_key", "apikey", "secret=", "token=", "bearer ",
            "private key", "-----begin", "jdbc:", "select *", "insert into ", "update ",
            "delete from ", "prompt:", "system prompt", "http://", "https://"
    );

    private final AgentToolActionPayloadMaterializationService payloadMaterializationService;

    /**
     * 物化 workspace 文件工具参数。
     *
     * <p>业务流程：</p>
     * <p>1. 先识别工具与操作，确保 `workspace.file.read` 只能对应 READ，`workspace.file.write` 只能对应 WRITE；</p>
     * <p>2. 校验相对路径是否仍在 workspace 安全边界内，拒绝绝对路径、URL、盘符、反斜杠、`..`、隐藏目录和凭据文件；</p>
     * <p>3. 对写入正文做大小限制和轻量 DLP marker 检查，避免明显密钥、SQL、Prompt、内部 endpoint 进入 store；</p>
     * <p>4. 构造或复用 `agent-payload:{runId}/{payloadKey}` 引用，把真实路径/正文只保存到服务端内部 body；</p>
     * <p>5. 返回低敏响应，响应只包含摘要、大小、状态和问题码，不包含 relativePath、content、workspaceRoot 或参数正文。</p>
     *
     * <p>该方法采用 fail-closed：任何关键校验失败都会返回 `BLOCKED_BEFORE_MATERIALIZATION`，并且不会写入 payload store。
     * 这样上游即使错误地把返回结果交给 writer，writer 也只能看到无法执行的低敏结论。</p>
     *
     * @param request workspace 文件工具 payload 物化请求。
     * @return 低敏物化结果；不会暴露真实路径、正文、root、SQL、prompt 或密钥。
     */
    public AgentWorkspaceFilePayloadMaterializationResponse materialize(
            AgentWorkspaceFilePayloadMaterializationRequest request) {
        List<String> issueCodes = new ArrayList<>();
        List<String> evidenceCodes = new ArrayList<>();
        if (request == null) {
            issueCodes.add("WORKSPACE_FILE_PAYLOAD_REQUEST_EMPTY");
            return blocked(null, null, null, null, null, null, false, false, issueCodes, evidenceCodes);
        }

        String toolName = safeText(request.toolName());
        WorkspaceFileOperation operation = resolveOperation(request.operation(), toolName, issueCodes, evidenceCodes);
        WorkspaceFilePathValidationResult path = validateRelativePath(request.relativePath(), issueCodes, evidenceCodes);
        WorkspaceFileContentValidationResult content = validateContent(operation, request, issueCodes, evidenceCodes);
        String payloadReference = resolvePayloadReference(request, operation, path, issueCodes, evidenceCodes);

        if (!issueCodes.isEmpty()) {
            return blocked(payloadReference, toolName, operation.name(), path.pathDigest(), content.contentSha256(),
                    content.contentSizeBytes(), content.contentReferenceProvided(), request.overwrite(),
                    issueCodes, evidenceCodes);
        }

        Map<String, Object> payloadBody = buildPayloadBody(request, operation, path, content);
        Optional<AgentToolActionPayloadRecord> record = payloadMaterializationService.materializePayloadBody(
                new AgentToolActionPayloadMaterializationService.AgentToolActionPayloadMaterializationRequest(
                        payloadReference,
                        safeText(request.runId()),
                        safeText(request.tenantId()),
                        safeText(request.projectId()),
                        safeText(request.actorId()),
                        toolName,
                        safeText(request.graphId()),
                        safeText(request.contractId()),
                        PAYLOAD_POLICY,
                        argumentNames(operation, content),
                        sensitiveArgumentNames(operation, content),
                        payloadBody,
                        request.ttl() == null ? DEFAULT_TTL : request.ttl()
                )
        );
        if (record.isEmpty()) {
            issueCodes.add("WORKSPACE_FILE_PAYLOAD_STORE_REJECTED");
            return blocked(payloadReference, toolName, operation.name(), path.pathDigest(), content.contentSha256(),
                    content.contentSizeBytes(), content.contentReferenceProvided(), request.overwrite(),
                    issueCodes, evidenceCodes);
        }

        evidenceCodes.add("WORKSPACE_FILE_PAYLOAD_MATERIALIZED");
        evidenceCodes.add(operation == WorkspaceFileOperation.READ
                ? "WORKSPACE_FILE_READ_ARGUMENTS_STORED"
                : "WORKSPACE_FILE_WRITE_ARGUMENTS_STORED");
        AgentToolActionPayloadRecord stored = record.get();
        return new AgentWorkspaceFilePayloadMaterializationResponse(
                true,
                "MATERIALIZED_FOR_WORKER_INTERNAL_READ",
                payloadReference,
                toolName,
                operation.name(),
                Boolean.TRUE.equals(stored.payloadBodyAvailable()),
                stored.payloadSizeBytes(),
                path.pathDigest(),
                content.contentSha256(),
                content.contentSizeBytes(),
                content.contentReferenceProvided(),
                Boolean.TRUE.equals(request.overwrite()),
                hasText(request.expectedSha256()),
                PAYLOAD_POLICY,
                List.copyOf(evidenceCodes),
                List.of(),
                List.of("WAIT_ARTIFACT_GRANT", "WAIT_DLP_SCAN", "WAIT_WORKER_RECEIPT")
        );
    }

    private WorkspaceFileOperation resolveOperation(String rawOperation,
                                                    String toolName,
                                                    List<String> issueCodes,
                                                    List<String> evidenceCodes) {
        String operationText = safeText(rawOperation);
        WorkspaceFileOperation operation = null;
        if (operationText != null) {
            try {
                operation = WorkspaceFileOperation.valueOf(operationText.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                issueCodes.add("WORKSPACE_FILE_OPERATION_UNSUPPORTED");
            }
        }
        if (operation == null && READ_TOOL.equals(toolName)) {
            operation = WorkspaceFileOperation.READ;
        }
        if (operation == null && WRITE_TOOL.equals(toolName)) {
            operation = WorkspaceFileOperation.WRITE;
        }
        if (operation == null) {
            issueCodes.add("WORKSPACE_FILE_OPERATION_REQUIRED");
            return WorkspaceFileOperation.READ;
        }
        if (operation == WorkspaceFileOperation.READ && !READ_TOOL.equals(toolName)) {
            issueCodes.add("WORKSPACE_FILE_READ_TOOL_NAME_MISMATCH");
        }
        if (operation == WorkspaceFileOperation.WRITE && !WRITE_TOOL.equals(toolName)) {
            issueCodes.add("WORKSPACE_FILE_WRITE_TOOL_NAME_MISMATCH");
        }
        evidenceCodes.add("WORKSPACE_FILE_OPERATION_RECOGNIZED");
        return operation;
    }

    private WorkspaceFilePathValidationResult validateRelativePath(String rawPath,
                                                                   List<String> issueCodes,
                                                                   List<String> evidenceCodes) {
        String path = safeText(rawPath);
        if (path == null) {
            issueCodes.add("WORKSPACE_FILE_RELATIVE_PATH_REQUIRED");
            return new WorkspaceFilePathValidationResult(null, null);
        }
        if (path.length() > MAX_RELATIVE_PATH_LENGTH) {
            issueCodes.add("WORKSPACE_FILE_RELATIVE_PATH_TOO_LONG");
        }
        String lowerPath = path.toLowerCase(Locale.ROOT);
        if (path.startsWith("/") || path.startsWith("~") || lowerPath.contains("://") || path.matches("^[A-Za-z]:.*")) {
            issueCodes.add("WORKSPACE_FILE_RELATIVE_PATH_NOT_ALLOWED");
        }
        if (path.contains("\\") || path.contains("\u0000")) {
            issueCodes.add("WORKSPACE_FILE_RELATIVE_PATH_UNSAFE_SEPARATOR");
        }

        List<String> normalizedSegments = new ArrayList<>();
        for (String segment : path.split("/", -1)) {
            String safeSegment = safeText(segment);
            if (safeSegment == null || ".".equals(safeSegment) || "..".equals(safeSegment)) {
                issueCodes.add("WORKSPACE_FILE_RELATIVE_PATH_ESCAPE_ATTEMPT");
                continue;
            }
            String lowerSegment = safeSegment.toLowerCase(Locale.ROOT);
            if (lowerSegment.startsWith(".") || DENIED_SEGMENTS.contains(lowerSegment)) {
                issueCodes.add("WORKSPACE_FILE_HIDDEN_OR_DENIED_SEGMENT");
            }
            if (DENIED_FILE_NAMES.contains(lowerSegment) || DENIED_SUFFIXES.stream().anyMatch(lowerSegment::endsWith)) {
                issueCodes.add("WORKSPACE_FILE_SENSITIVE_FILE_NAME_DENIED");
            }
            normalizedSegments.add(safeSegment);
        }

        if (normalizedSegments.isEmpty()) {
            issueCodes.add("WORKSPACE_FILE_RELATIVE_PATH_EMPTY_AFTER_NORMALIZE");
            return new WorkspaceFilePathValidationResult(null, null);
        }
        String normalizedPath = String.join("/", normalizedSegments);
        evidenceCodes.add("WORKSPACE_FILE_RELATIVE_PATH_VALIDATED");
        return new WorkspaceFilePathValidationResult(normalizedPath, sha256(normalizedPath));
    }

    private WorkspaceFileContentValidationResult validateContent(
            WorkspaceFileOperation operation,
            AgentWorkspaceFilePayloadMaterializationRequest request,
            List<String> issueCodes,
            List<String> evidenceCodes) {
        if (operation == WorkspaceFileOperation.READ) {
            evidenceCodes.add("WORKSPACE_FILE_READ_CONTENT_NOT_REQUIRED");
            return new WorkspaceFileContentValidationResult(null, 0, false, false);
        }
        String content = request.content();
        String contentReference = safeText(request.contentReference());
        boolean hasInlineContent = content != null;
        boolean hasContentReference = contentReference != null;
        if (!hasInlineContent && !hasContentReference) {
            issueCodes.add("WORKSPACE_FILE_WRITE_CONTENT_REQUIRED");
        }
        if (hasInlineContent && hasContentReference) {
            issueCodes.add("WORKSPACE_FILE_WRITE_CONTENT_AMBIGUOUS");
        }
        if (hasContentReference) {
            evidenceCodes.add("WORKSPACE_FILE_WRITE_CONTENT_REFERENCE_PROVIDED");
            return new WorkspaceFileContentValidationResult(null, 0, true, false);
        }
        if (!hasInlineContent) {
            return new WorkspaceFileContentValidationResult(null, 0, false, false);
        }
        int contentBytes = content.getBytes(StandardCharsets.UTF_8).length;
        int maxBytes = request.maxInlineContentBytes() == null
                ? DEFAULT_MAX_INLINE_CONTENT_BYTES
                : Math.max(1, request.maxInlineContentBytes());
        if (contentBytes > maxBytes) {
            issueCodes.add("WORKSPACE_FILE_WRITE_CONTENT_TOO_LARGE");
        }
        String lowerContent = content.toLowerCase(Locale.ROOT);
        for (String marker : INLINE_CONTENT_RISK_MARKERS) {
            if (lowerContent.contains(marker)) {
                issueCodes.add("WORKSPACE_FILE_WRITE_CONTENT_RISK_MARKER_DETECTED");
                break;
            }
        }
        evidenceCodes.add("WORKSPACE_FILE_WRITE_INLINE_CONTENT_CHECKED");
        return new WorkspaceFileContentValidationResult(sha256(content), contentBytes, false, true);
    }

    private String resolvePayloadReference(AgentWorkspaceFilePayloadMaterializationRequest request,
                                           WorkspaceFileOperation operation,
                                           WorkspaceFilePathValidationResult path,
                                           List<String> issueCodes,
                                           List<String> evidenceCodes) {
        String explicitReference = safeText(request.payloadReference());
        if (explicitReference != null) {
            evidenceCodes.add("WORKSPACE_FILE_PAYLOAD_REFERENCE_PROVIDED_BY_CALLER");
            return explicitReference;
        }
        String payloadKey = safeText(request.payloadKey());
        if (payloadKey == null && path.normalizedPath() != null) {
            payloadKey = "workspace-file-" + operation.name().toLowerCase(Locale.ROOT) + ":" + path.pathDigest().substring(0, 16);
        }
        Optional<String> built = payloadMaterializationService.buildPayloadReference(request.runId(), payloadKey);
        if (built.isEmpty()) {
            issueCodes.add("WORKSPACE_FILE_PAYLOAD_REFERENCE_BUILD_FAILED");
            return null;
        }
        evidenceCodes.add("WORKSPACE_FILE_PAYLOAD_REFERENCE_BUILT");
        return built.get();
    }

    private Map<String, Object> buildPayloadBody(AgentWorkspaceFilePayloadMaterializationRequest request,
                                                 WorkspaceFileOperation operation,
                                                 WorkspaceFilePathValidationResult path,
                                                 WorkspaceFileContentValidationResult content) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operation", operation.name());
        body.put("relativePath", path.normalizedPath());
        body.put("pathDigest", path.pathDigest());
        body.put("overwrite", Boolean.TRUE.equals(request.overwrite()));
        if (hasText(request.expectedSha256())) {
            body.put("expectedSha256", request.expectedSha256().trim());
        }
        if (operation == WorkspaceFileOperation.WRITE && content.inlineContentProvided()) {
            body.put("content", request.content());
            body.put("contentSha256", content.contentSha256());
            body.put("contentSizeBytes", content.contentSizeBytes());
        }
        if (operation == WorkspaceFileOperation.WRITE && content.contentReferenceProvided()) {
            body.put("contentReference", safeText(request.contentReference()));
            body.put("contentReferenceDigest", sha256(request.contentReference().trim()));
        }
        return body;
    }

    private List<String> argumentNames(
            WorkspaceFileOperation operation,
            WorkspaceFileContentValidationResult content) {
        List<String> names = new ArrayList<>();
        names.add("operation");
        names.add("relativePath");
        names.add("overwrite");
        names.add("expectedSha256");
        if (operation == WorkspaceFileOperation.WRITE && content.inlineContentProvided()) {
            names.add("content");
        }
        if (operation == WorkspaceFileOperation.WRITE && content.contentReferenceProvided()) {
            names.add("contentReference");
        }
        return names;
    }

    private List<String> sensitiveArgumentNames(
            WorkspaceFileOperation operation,
            WorkspaceFileContentValidationResult content) {
        List<String> names = new ArrayList<>();
        names.add("relativePath");
        if (operation == WorkspaceFileOperation.WRITE && content.inlineContentProvided()) {
            names.add("content");
        }
        if (operation == WorkspaceFileOperation.WRITE && content.contentReferenceProvided()) {
            names.add("contentReference");
        }
        return names;
    }

    private AgentWorkspaceFilePayloadMaterializationResponse blocked(String payloadReference,
                                                                     String toolName,
                                                                     String operation,
                                                                     String pathDigest,
                                                                     String contentSha256,
                                                                     Integer contentSizeBytes,
                                                                     boolean contentReferenceProvided,
                                                                     Boolean overwrite,
                                                                     List<String> issueCodes,
                                                                     List<String> evidenceCodes) {
        return new AgentWorkspaceFilePayloadMaterializationResponse(
                false,
                "BLOCKED_BEFORE_MATERIALIZATION",
                payloadReference,
                toolName,
                operation,
                false,
                0,
                pathDigest,
                contentSha256,
                contentSizeBytes == null ? 0 : Math.max(0, contentSizeBytes),
                contentReferenceProvided,
                Boolean.TRUE.equals(overwrite),
                false,
                PAYLOAD_POLICY,
                List.copyOf(evidenceCodes),
                List.copyOf(issueCodes),
                List.of("FIX_REQUEST_ARGUMENTS", "RETRY_AFTER_POLICY_REVIEW")
        );
    }

    /**
     * Workspace 文件工具参数物化请求。
     *
     * @param payloadReference 调用方指定的 `agent-payload:` 引用；为空时由服务端根据 runId 和 payloadKey 构造。
     * @param runId Agent run ID，必须与 payloadReference 中的 runId 绑定，用于防止跨运行复用工具参数。
     * @param payloadKey run 内部的载荷键；为空时服务端会使用操作和路径摘要生成低敏键。
     * @param tenantId 租户 ID，用于后续 worker/verifier 做多租户隔离。
     * @param projectId 项目 ID，用于后续 worker/verifier 做项目级授权和审计。
     * @param actorId 用户、服务账号或 Agent actor ID，用于 SELF 范围和审计追踪。
     * @param toolName 工具编码，只允许 `workspace.file.read` 或 `workspace.file.write`。
     * @param operation 文件操作，支持 READ/WRITE；为空时可由 toolName 推导。
     * @param graphId 来源执行图 ID，用于把参数与 Agent 计划图版本绑定。
     * @param contractId durable action contract ID，用于把参数与 outbox 契约绑定。
     * @param relativePath workspace 内相对路径；真实值只进入服务端内部 body，不会回显到响应。
     * @param content 写入正文；仅 WRITE 可用，只进入服务端内部 body，不会进入响应、事件或 projection。
     * @param contentReference 写入正文引用；用于未来接 artifact/MinIO/KMS，响应只返回“已提供”状态，不回显引用值。
     * @param overwrite 是否允许覆盖已有文件；真实执行前 worker 仍需做 expectedSha256/版本冲突检查。
     * @param expectedSha256 可选的乐观并发校验值，防止用户基于旧文件版本覆盖新内容。
     * @param maxInlineContentBytes 单次内联写入大小上限；为空时使用 256KB 的安全默认值。
     * @param ttl payload store TTL；为空时使用 30 分钟，避免旧工具参数长期可执行。
     */
    public record AgentWorkspaceFilePayloadMaterializationRequest(
            String payloadReference,
            String runId,
            String payloadKey,
            String tenantId,
            String projectId,
            String actorId,
            String toolName,
            String operation,
            String graphId,
            String contractId,
            String relativePath,
            String content,
            String contentReference,
            Boolean overwrite,
            String expectedSha256,
            Integer maxInlineContentBytes,
            Duration ttl
    ) {
    }

    /**
     * Workspace 文件工具参数物化响应。
     *
     * <p>该响应是低敏 API/事件友好 DTO：它故意不包含 relativePath、workspaceRoot、content、contentReference 原值、
     * prompt、SQL、样本数据、模型输出、凭据或内部 endpoint。调用方只能通过 payloadReference 让受控 worker
     * 在服务端内部读取真实参数，并继续等待 artifact grant、DLP 扫描、审批和 worker receipt。</p>
     *
     * @param materialized 是否已经成功写入服务端 payload store。
     * @param materializationState 物化状态，成功时为 MATERIALIZED_FOR_WORKER_INTERNAL_READ。
     * @param payloadReference `agent-payload:` 引用，可进入 durable command 和审批摘要。
     * @param toolName 工具编码，不包含参数值。
     * @param operation READ/WRITE 操作。
     * @param payloadBodyAvailable store 内部是否已有真实 body。
     * @param payloadSizeBytes 内部 body 字节数，只用于治理和容量控制。
     * @param pathDigest 相对路径摘要，用于排障关联，不能反推路径明文。
     * @param contentSha256 写入正文摘要；READ 或 contentReference 写入时为空。
     * @param contentSizeBytes 写入正文大小；不包含正文。
     * @param contentReferenceProvided 是否提供了外部正文引用；不回显引用值。
     * @param overwrite 覆盖策略摘要。
     * @param expectedSha256Provided 是否提供乐观并发校验值；不回显校验值。
     * @param payloadPolicy 当前 payload 治理策略。
     * @param evidenceCodes 低敏证据码，说明通过了哪些控制面校验。
     * @param issueCodes 低敏问题码，说明为什么未能物化或需要人工/策略修正。
     * @param recommendedActions 后续建议动作，例如等待 artifact grant 或 worker receipt。
     */
    public record AgentWorkspaceFilePayloadMaterializationResponse(
            Boolean materialized,
            String materializationState,
            String payloadReference,
            String toolName,
            String operation,
            Boolean payloadBodyAvailable,
            Integer payloadSizeBytes,
            String pathDigest,
            String contentSha256,
            Integer contentSizeBytes,
            Boolean contentReferenceProvided,
            Boolean overwrite,
            Boolean expectedSha256Provided,
            String payloadPolicy,
            List<String> evidenceCodes,
            List<String> issueCodes,
            List<String> recommendedActions
    ) {
        public AgentWorkspaceFilePayloadMaterializationResponse {
            materialized = Boolean.TRUE.equals(materialized);
            payloadBodyAvailable = Boolean.TRUE.equals(payloadBodyAvailable);
            payloadSizeBytes = Math.max(0, payloadSizeBytes == null ? 0 : payloadSizeBytes);
            contentSizeBytes = Math.max(0, contentSizeBytes == null ? 0 : contentSizeBytes);
            contentReferenceProvided = Boolean.TRUE.equals(contentReferenceProvided);
            overwrite = Boolean.TRUE.equals(overwrite);
            expectedSha256Provided = Boolean.TRUE.equals(expectedSha256Provided);
            evidenceCodes = evidenceCodes == null ? List.of() : List.copyOf(evidenceCodes);
            issueCodes = issueCodes == null ? List.of() : List.copyOf(issueCodes);
            recommendedActions = recommendedActions == null ? List.of() : List.copyOf(recommendedActions);
        }
    }
}
