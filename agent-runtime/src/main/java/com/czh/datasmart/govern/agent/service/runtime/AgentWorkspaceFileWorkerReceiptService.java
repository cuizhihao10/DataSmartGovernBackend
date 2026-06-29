/**
 * @Author : Cui
 * @Date: 2026/06/29 00:00
 * @Description DataSmart Govern Backend - AgentWorkspaceFileWorkerReceiptService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionCommandWorkerReceiptRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionCommandWorkerReceiptResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentWorkspaceFileWorkerReceiptRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentWorkspaceFileWorkerReceiptResponse;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Workspace 文件工具 worker 回执适配服务。
 *
 * <p>该服务位于“workspace 文件 worker”和“通用 command worker receipt 服务”之间，是一个有意保持很薄的
 * 防腐层。它不执行文件读写、不读取文件正文、不打开 artifact，也不直接写 runtime event；真正的回执落库仍委托
 * {@link AgentToolActionCommandWorkerReceiptService} 完成。它只负责 workspace 文件工具特有的几项前置校验：</p>
 *
 * <p>1. `payloadReference` 必须存在于服务端 payload store，且 payload body 已物化；</p>
 * <p>2. payload 所属 run、tenant、project、actor、tool 必须与 worker 回执一致；</p>
 * <p>3. READ/WRITE 操作必须与 payload body 和 toolCode 匹配；</p>
 * <p>4. `EXECUTION_SUCCEEDED` 必须声明可审计 artifact，因为文件读/写结果不能只靠一段文字消息证明。</p>
 *
 * <p>这样做的产品价值是把 workspace 文件工具推进到“可回执、可审计、可恢复事实查询”的闭口状态，同时避免
 * 直接实现真实文件 worker 带来的路径、沙箱、DLP、对象存储和并发写入复杂度。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentWorkspaceFileWorkerReceiptService {

    public static final String PAYLOAD_POLICY =
            "WORKSPACE_FILE_WORKER_RECEIPT_SUMMARY_ONLY_NO_PATH_NO_CONTENT_NO_ARGUMENTS_NO_STDIO_NO_PROMPT_NO_SQL";

    private static final String READ_TOOL = "workspace.file.read";
    private static final String WRITE_TOOL = "workspace.file.write";
    private static final String AGENT_PAYLOAD_PREFIX = "agent-payload:";
    private static final Set<String> SUPPORTED_OUTCOMES = Set.of(
            "FAILED_PRECHECK",
            "WORKER_PRECHECK_PASSED",
            "EXECUTION_SUCCEEDED",
            "EXECUTION_FAILED",
            "EXECUTION_SKIPPED",
            "CAPACITY_LIMITED",
            "COMPENSATION_REQUIRED"
    );

    private final AgentToolActionPayloadStore payloadStore;
    private final AgentToolActionCommandWorkerReceiptService commandWorkerReceiptService;

    /**
     * 接收 workspace 文件 worker 回执，并委托通用 command worker receipt 服务写入 timeline。
     *
     * <p>该方法的副作用只有一个：当 workspace 专用校验通过后，调用通用 receipt 服务写入 runtime event projection
     * 和 worker receipt index。它不会读取 payload body 中的 relativePath/content/contentReference 原值，也不会把这些
     * 字段转交给通用回执。通用回执看到的仍然只是 commandId、lease、outcome、artifactReference 和低敏摘要。</p>
     *
     * @param sessionId Agent 会话 ID，来自内部路由。
     * @param runId Agent run ID，来自内部路由。
     * @param traceId 链路追踪 ID。
     * @param request workspace 文件 worker 回执请求。
     * @return workspace 文件回执低敏响应。
     */
    public AgentWorkspaceFileWorkerReceiptResponse receive(String sessionId,
                                                           String runId,
                                                           String traceId,
                                                           AgentWorkspaceFileWorkerReceiptRequest request) {
        if (request == null || request.commandWorkerReceipt() == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "workspace 文件 worker 回执请求体和 commandWorkerReceipt 不能为空");
        }
        AgentToolActionCommandWorkerReceiptRequest receipt = request.commandWorkerReceipt();
        String operation = resolveOperation(request.operation(), receipt.toolCode());
        String payloadReference = requiredPayloadReference(request.payloadReference());
        AgentToolActionPayloadRecord payloadRecord = payloadRecord(payloadReference);
        validatePayloadBinding(payloadRecord, runId, receipt, operation);
        validateOutcomeContract(operation, receipt);

        AgentToolActionCommandWorkerReceiptResponse delegated =
                commandWorkerReceiptService.receive(sessionId, runId, traceId, receipt);
        return response(payloadReference, operation, receipt, delegated);
    }

    private AgentWorkspaceFileWorkerReceiptResponse response(String payloadReference,
                                                             String operation,
                                                             AgentToolActionCommandWorkerReceiptRequest receipt,
                                                             AgentToolActionCommandWorkerReceiptResponse delegated) {
        List<String> evidenceCodes = new ArrayList<>();
        evidenceCodes.add("WORKSPACE_FILE_PAYLOAD_RECORD_FOUND");
        evidenceCodes.add("WORKSPACE_FILE_PAYLOAD_BODY_AVAILABLE");
        evidenceCodes.add("WORKSPACE_FILE_PAYLOAD_SCOPE_VERIFIED");
        evidenceCodes.add("WORKSPACE_FILE_OPERATION_VERIFIED");
        evidenceCodes.add("COMMAND_WORKER_RECEIPT_DELEGATED");
        if (delegated.duplicate()) {
            evidenceCodes.add("COMMAND_WORKER_RECEIPT_IDEMPOTENT_DUPLICATE");
        }
        if (Boolean.TRUE.equals(receipt.artifactAvailable())) {
            evidenceCodes.add("WORKSPACE_FILE_ARTIFACT_REFERENCE_RECORDED");
        }
        return new AgentWorkspaceFileWorkerReceiptResponse(
                delegated.accepted(),
                delegated.duplicate(),
                delegated.identityKey(),
                delegated.eventType(),
                delegated.outcome(),
                delegated.sideEffectExecuted(),
                receipt.commandId(),
                payloadReference,
                "sha256:" + sha256(payloadReference).substring(0, 20),
                receipt.toolCode(),
                operation,
                receipt.artifactReferenceType(),
                receipt.artifactAvailable(),
                evidenceCodes,
                List.of(),
                List.of(
                        "通过 worker receipt index 查询 commandId，确认 workspace 文件工具执行事实已经进入可恢复视图。",
                        "如果 artifactAvailable=true，后续读取结果正文仍必须走 artifact grant 和 final-check。"
                ),
                PAYLOAD_POLICY
        );
    }

    /**
     * 校验 payloadReference 形态和服务端登记事实。
     *
     * <p>这里直接读取 payload store，而不是通过 proposal verifier。原因是 workspace worker receipt 发生在
     * command 已经入箱、worker 已经拿到 payloadReference 之后，当前上下文没有完整 proposal 对象。我们只需要确认
     * 该引用确实是服务端登记过的内部执行参数，并且仍然在 TTL 内。</p>
     */
    private AgentToolActionPayloadRecord payloadRecord(String payloadReference) {
        AgentToolActionPayloadRecord record = payloadStore.findByReference(payloadReference)
                .orElseThrow(() -> new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                        "workspace 文件 worker 回执引用的 payloadReference 尚未物化"));
        if (record.expired(Instant.now())) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "workspace 文件 worker 回执引用的 payloadReference 已过期");
        }
        if (!Boolean.TRUE.equals(record.payloadBodyAvailable()) || record.payloadBody().isEmpty()) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "workspace 文件 worker 回执要求 payload body 已物化，不能只使用 envelope");
        }
        return record;
    }

    private void validatePayloadBinding(AgentToolActionPayloadRecord record,
                                        String runId,
                                        AgentToolActionCommandWorkerReceiptRequest receipt,
                                        String operation) {
        List<String> issues = new ArrayList<>();
        if (!same(record.runId(), runId)) {
            issues.add("WORKSPACE_FILE_PAYLOAD_RUN_MISMATCH");
        }
        if (!same(record.tenantId(), receipt.tenantId())) {
            issues.add("WORKSPACE_FILE_PAYLOAD_TENANT_MISMATCH");
        }
        if (!same(record.projectId(), receipt.projectId())) {
            issues.add("WORKSPACE_FILE_PAYLOAD_PROJECT_MISMATCH");
        }
        if (!same(record.actorId(), receipt.actorId())) {
            issues.add("WORKSPACE_FILE_PAYLOAD_ACTOR_MISMATCH");
        }
        if (!same(record.toolName(), receipt.toolCode())) {
            issues.add("WORKSPACE_FILE_PAYLOAD_TOOL_MISMATCH");
        }
        String payloadOperation = safeText(String.valueOf(record.payloadBody().get("operation")));
        if (!same(operation, payloadOperation)) {
            issues.add("WORKSPACE_FILE_PAYLOAD_OPERATION_MISMATCH");
        }
        if (!issues.isEmpty()) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "workspace 文件 worker 回执与服务端 payload fact 不一致：" + String.join(",", issues));
        }
    }

    private void validateOutcomeContract(String operation, AgentToolActionCommandWorkerReceiptRequest receipt) {
        String outcome = normalizeCode(receipt.outcome());
        if (!SUPPORTED_OUTCOMES.contains(outcome)) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "workspace 文件 worker 回执 outcome 不在受支持集合内");
        }
        if ("EXECUTION_SUCCEEDED".equals(outcome) && !Boolean.TRUE.equals(receipt.artifactAvailable())) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "workspace 文件 worker 执行成功时必须登记低敏 artifactReference，不能只靠 message 证明结果");
        }
        if ("EXECUTION_SUCCEEDED".equals(outcome) && safeText(receipt.artifactReference()) == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "workspace 文件 worker 执行成功时 artifactReference 不能为空");
        }
        if ("READ".equals(operation) && "EXECUTION_SUCCEEDED".equals(outcome)
                && safeText(receipt.artifactReferenceType()) == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "workspace 文件读取成功时必须提供 artifactReferenceType，后续正文读取需要 artifact grant");
        }
    }

    private String resolveOperation(String rawOperation, String toolCode) {
        String operation = normalizeCode(rawOperation);
        String tool = safeText(toolCode);
        if (operation == null && READ_TOOL.equals(tool)) {
            operation = "READ";
        }
        if (operation == null && WRITE_TOOL.equals(tool)) {
            operation = "WRITE";
        }
        if (!"READ".equals(operation) && !"WRITE".equals(operation)) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "workspace 文件 worker 回执 operation 只支持 READ 或 WRITE");
        }
        if ("READ".equals(operation) && !READ_TOOL.equals(tool)) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "workspace 文件 READ 回执必须使用 workspace.file.read 工具编码");
        }
        if ("WRITE".equals(operation) && !WRITE_TOOL.equals(tool)) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "workspace 文件 WRITE 回执必须使用 workspace.file.write 工具编码");
        }
        return operation;
    }

    private String requiredPayloadReference(String value) {
        String reference = safeText(value);
        if (reference == null || !reference.startsWith(AGENT_PAYLOAD_PREFIX)
                || reference.contains("\n") || reference.contains("\r") || reference.contains("\\")
                || reference.contains("{") || reference.contains("}") || reference.toLowerCase(Locale.ROOT).contains("://")) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "workspace 文件 worker 回执必须提供安全的 agent-payload 引用");
        }
        return reference;
    }

    private boolean same(String left, Long right) {
        return same(left, right == null ? null : String.valueOf(right));
    }

    private boolean same(String left, String right) {
        String normalizedLeft = safeText(left);
        String normalizedRight = safeText(right);
        return normalizedLeft != null && normalizedLeft.equals(normalizedRight);
    }

    private String normalizeCode(String value) {
        String text = safeText(value);
        return text == null ? null : text.toUpperCase(Locale.ROOT).replace('-', '_').replace('.', '_');
    }

    private String safeText(String value) {
        return value == null || value.isBlank() || "null".equalsIgnoreCase(value.trim()) ? null : value.trim();
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 缺少 SHA-256，无法生成 workspace 文件回执摘要", exception);
        }
    }
}
