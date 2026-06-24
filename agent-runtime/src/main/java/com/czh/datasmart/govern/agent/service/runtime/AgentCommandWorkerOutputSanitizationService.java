/**
 * @Author : Cui
 * @Date: 2026/06/24 21:10
 * @Description DataSmart Govern Backend - AgentCommandWorkerOutputSanitizationService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentCommandWorkerOutputSanitizeRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentCommandWorkerOutputSanitizeResponse;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 受控命令 worker 输出片段净化服务。
 *
 * <p>本服务位于真实 sandbox/worker 与 artifact store 之间，解决的是“输出正文如何安全地变成预览候选”的问题。
 * 它不写 runtime event，不生成 artifactReference，不连接 MinIO，也不把输出交给模型。它只把 worker 提交的短生命周期
 * 输出片段做净化，得到一个可继续传给 5.101 final-check 的 `sanitizedPreviewText`。</p>
 *
 * <p>这样拆分的原因是：receipt 层只能记录低敏执行事实，不能接收 stdout/stderr；artifact final-check 层只能复核
 * 已经脱敏的候选预览，不能负责理解原始输出。中间必须有一个明确的 output sanitization 边界，专门处理大小、行数、
 * 控制字符、敏感标记和预览裁剪。</p>
 */
@Service
public class AgentCommandWorkerOutputSanitizationService {

    /**
     * 当前响应承诺的载荷策略。
     */
    public static final String PAYLOAD_POLICY =
            "OUTPUT_SANITIZATION_SAFE_PREVIEW_ONLY_NO_RAW_STDIO_NO_COMMAND_LINE_NO_PROMPT_NO_SQL_NO_SECRET_NO_URL";

    private static final int DEFAULT_WORKER_OUTPUT_LIMIT_BYTES = 64 * 1024;
    private static final int HARD_RAW_INPUT_BYTES = 256 * 1024;
    private static final int DEFAULT_PREVIEW_BYTES = 32 * 1024;
    private static final int HARD_PREVIEW_BYTES = 64 * 1024;
    private static final int MAX_LINE_COUNT = 240;
    private static final int MAX_LINE_LENGTH = 500;
    private static final String REDACTED_LINE = "[REDACTED_SENSITIVE_LINE]";
    private static final Set<String> ALLOWED_OUTPUT_CHANNELS = Set.of("STDOUT", "STDERR", "COMBINED_LOG");

    /**
     * 净化一段 worker 输出片段。
     *
     * @param request worker 提交的内部净化请求。请求中允许出现 rawOutputChunk，但该字段不会进入响应、事件或投影。
     * @return 安全预览候选和低敏治理元数据。
     */
    public AgentCommandWorkerOutputSanitizeResponse sanitize(AgentCommandWorkerOutputSanitizeRequest request) {
        validateRequest(request);

        String commandId = safeText(request.commandId());
        String outputChannel = normalizeChannel(request.outputChannel());
        String rawOutput = request.rawOutputChunk();
        int rawInputBytes = utf8Bytes(rawOutput);
        TextClipResult inputClip = clipUtf8(rawOutput, Math.min(rawInputBytes, HARD_RAW_INPUT_BYTES));
        int workerOutputLimit = resolveWorkerOutputLimit(request.workerOutputByteLimitBytes());
        int previewLimitBytes = resolvePreviewLimit(request.requestedPreviewBytes(), workerOutputLimit);
        SanitizedTextResult sanitized = sanitizeText(inputClip.text());
        TextClipResult previewClip = clipUtf8(sanitized.text(), previewLimitBytes);

        List<String> evidenceCodes = new ArrayList<>();
        evidenceCodes.add("RAW_OUTPUT_PROCESSED_IN_MEMORY_ONLY");
        evidenceCodes.add("RAW_OUTPUT_NOT_RETURNED");
        evidenceCodes.add("OUTPUT_CHANNEL_ALLOWED");
        evidenceCodes.add("CONTROL_CHARACTERS_NORMALIZED");
        evidenceCodes.add("SENSITIVE_LINES_REDACTED_BEFORE_PREVIEW");
        evidenceCodes.add("SAFE_PREVIEW_CLIPPED_BY_HOST_POLICY");
        if (Boolean.TRUE.equals(request.rawOutputTruncatedByWorker())) {
            evidenceCodes.add("RAW_OUTPUT_ALREADY_TRUNCATED_BY_WORKER");
        }
        if (inputClip.truncated()) {
            evidenceCodes.add("RAW_INPUT_TRUNCATED_TO_HOST_HARD_LIMIT");
        }
        if (previewClip.truncated()) {
            evidenceCodes.add("SAFE_PREVIEW_TRUNCATED_TO_BYTE_LIMIT");
        }

        List<String> issueCodes = new ArrayList<>();
        if (inputClip.truncated()) {
            issueCodes.add("RAW_INPUT_EXCEEDED_HOST_HARD_LIMIT");
        }
        if (sanitized.sensitiveLineCount() > 0) {
            issueCodes.add("SENSITIVE_OUTPUT_LINES_REDACTED");
        }
        if (sanitized.omittedLineCount() > 0) {
            issueCodes.add("OUTPUT_LINES_OMITTED_BY_HOST_POLICY");
        }
        if (previewClip.text().isBlank()) {
            issueCodes.add("NO_SAFE_PREVIEW_AVAILABLE");
            return response(
                    false,
                    "DENIED_NO_SAFE_PREVIEW_AFTER_SANITIZATION",
                    commandId,
                    outputChannel,
                    null,
                    previewLimitBytes,
                    0,
                    false,
                    rawInputBytes,
                    inputClip.bytes(),
                    workerOutputLimit,
                    request,
                    sanitized,
                    evidenceCodes,
                    issueCodes,
                    List.of("输出片段经敏感行重写后没有可展示内容，请仅登记 artifactReference，并等待人工或 DLP 复核。")
            );
        }

        return response(
                true,
                sanitized.sensitiveLineCount() > 0
                        ? "ACCEPTED_SAFE_PREVIEW_WITH_REDACTIONS"
                        : "ACCEPTED_SAFE_PREVIEW",
                commandId,
                outputChannel,
                previewClip.text(),
                previewLimitBytes,
                previewClip.bytes(),
                previewClip.truncated(),
                rawInputBytes,
                inputClip.bytes(),
                workerOutputLimit,
                request,
                sanitized,
                evidenceCodes,
                issueCodes,
                List.of(
                        "将 sanitizedPreviewText 作为 artifact safe preview 候选传给 body-read-final-checks，不要写入 runtime event 或 receipt。",
                        "完整输出必须先落入受权限保护的 artifact store，并通过 metadata/body-read/final-check 三层门禁读取。",
                        "如果 sensitiveLineCount 大于 0，建议在任务结果页提示用户输出已被安全重写。"
                )
        );
    }

    private AgentCommandWorkerOutputSanitizeResponse response(
            boolean accepted,
            String decision,
            String commandId,
            String outputChannel,
            String sanitizedPreviewText,
            int previewLimitBytes,
            int previewBytes,
            boolean previewTruncated,
            int rawInputBytes,
            int processedInputBytes,
            int workerOutputLimit,
            AgentCommandWorkerOutputSanitizeRequest request,
            SanitizedTextResult sanitized,
            List<String> evidenceCodes,
            List<String> issueCodes,
            List<String> recommendedActions) {
        return new AgentCommandWorkerOutputSanitizeResponse(
                accepted,
                decision,
                commandId,
                outputChannel,
                false,
                sanitizedPreviewText != null,
                sanitizedPreviewText,
                previewLimitBytes,
                previewBytes,
                previewTruncated,
                rawInputBytes,
                processedInputBytes,
                workerOutputLimit,
                Boolean.TRUE.equals(request.rawOutputTruncatedByWorker()),
                sanitized.sensitiveLineCount(),
                sanitized.omittedLineCount(),
                sanitized.controlCharacterCount(),
                "text/plain; charset=utf-8",
                List.copyOf(evidenceCodes),
                List.copyOf(issueCodes),
                List.copyOf(recommendedActions),
                PAYLOAD_POLICY
        );
    }

    /**
     * 校验请求结构和低敏控制字段。
     */
    private void validateRequest(AgentCommandWorkerOutputSanitizeRequest request) {
        if (request == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "command worker 输出净化请求体不能为空");
        }
        if (safeText(request.commandId()) == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "command worker 输出净化必须提供 commandId");
        }
        if (!ALLOWED_OUTPUT_CHANNELS.contains(normalizeChannel(request.outputChannel()))) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "outputChannel 仅允许 STDOUT、STDERR 或 COMBINED_LOG");
        }
        if (request.rawOutputChunk() == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "rawOutputChunk 不能为空；如果 worker 没有输出，请不要调用输出净化接口");
        }
        if (request.workerOutputByteLimitBytes() != null && request.workerOutputByteLimitBytes() <= 0) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "workerOutputByteLimitBytes 必须大于 0");
        }
        if (request.requestedPreviewBytes() != null && request.requestedPreviewBytes() <= 0) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "requestedPreviewBytes 必须大于 0");
        }
        rejectSensitiveControlText(request.rawOutputEncoding(), "rawOutputEncoding");
        rejectSensitiveControlText(request.runId(), "runId");
        rejectSensitiveControlText(request.sessionId(), "sessionId");
        rejectSensitiveControlText(request.toolCode(), "toolCode");
        rejectSensitiveControlText(request.requesterComponent(), "requesterComponent");
    }

    /**
     * 对输出正文逐行净化。
     *
     * <p>行级处理比整段替换更适合命令输出：一段输出里通常只有少数行包含 URL、token 或 SQL，整段拒绝会让任务结果页
     * 失去全部诊断价值；行级重写可以保留低风险上下文，同时把危险行变成固定占位符。</p>
     */
    private SanitizedTextResult sanitizeText(String rawText) {
        String normalized = rawText.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        StringBuilder builder = new StringBuilder();
        int sensitiveLineCount = 0;
        int omittedLineCount = 0;
        int controlCharacterCount = 0;
        int handledLines = Math.min(lines.length, MAX_LINE_COUNT);
        for (int index = 0; index < handledLines; index++) {
            String line = lines[index];
            ControlCleanResult controlCleanResult = cleanControlCharacters(line);
            controlCharacterCount += controlCleanResult.replacedCount();
            String cleanedLine = truncateLine(controlCleanResult.text());
            if (looksSensitiveOutputLine(cleanedLine)) {
                sensitiveLineCount++;
                cleanedLine = REDACTED_LINE;
            }
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(cleanedLine);
        }
        if (lines.length > MAX_LINE_COUNT) {
            omittedLineCount += lines.length - MAX_LINE_COUNT;
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append("[OMITTED_LINES_BY_HOST_POLICY]");
        }
        return new SanitizedTextResult(builder.toString().trim(), sensitiveLineCount, omittedLineCount, controlCharacterCount);
    }

    private ControlCleanResult cleanControlCharacters(String text) {
        StringBuilder builder = new StringBuilder();
        int replaced = 0;
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            if (Character.isISOControl(codePoint) && codePoint != '\t') {
                builder.append(' ');
                replaced++;
            } else {
                builder.appendCodePoint(codePoint);
            }
            offset += Character.charCount(codePoint);
        }
        return new ControlCleanResult(builder.toString(), replaced);
    }

    private String truncateLine(String line) {
        if (line.length() <= MAX_LINE_LENGTH) {
            return line;
        }
        return line.substring(0, MAX_LINE_LENGTH) + " [LINE_TRUNCATED]";
    }

    /**
     * 按 UTF-8 字节数裁剪字符串，避免中文或多字节字符被截断成非法文本。
     */
    private TextClipResult clipUtf8(String text, int maxBytes) {
        byte[] allBytes = text.getBytes(StandardCharsets.UTF_8);
        if (allBytes.length <= maxBytes) {
            return new TextClipResult(text, allBytes.length, false);
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
        return new TextClipResult(builder.toString(), usedBytes, true);
    }

    private int resolveWorkerOutputLimit(Integer value) {
        if (value == null) {
            return DEFAULT_WORKER_OUTPUT_LIMIT_BYTES;
        }
        return Math.min(value, HARD_RAW_INPUT_BYTES);
    }

    private int resolvePreviewLimit(Integer requestedPreviewBytes, int workerOutputLimit) {
        int requested = requestedPreviewBytes == null ? DEFAULT_PREVIEW_BYTES : requestedPreviewBytes;
        return Math.min(Math.min(requested, workerOutputLimit), HARD_PREVIEW_BYTES);
    }

    private int utf8Bytes(String text) {
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }

    private String normalizeChannel(String value) {
        String text = safeText(value);
        if (text == null) {
            return "";
        }
        return text.toUpperCase(Locale.ROOT).replace('-', '_').replace('.', '_');
    }

    private void rejectSensitiveControlText(String value, String fieldName) {
        if (looksSensitiveControlText(value)) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    fieldName + " 疑似包含 URL、SQL、prompt、凭据、对象定位或内部 endpoint，已拒绝进入输出净化链路");
        }
    }

    private boolean looksSensitiveControlText(String value) {
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
                || lower.contains("credential")
                || lower.contains("api_key")
                || lower.contains("prompt:")
                || lower.contains("http://")
                || lower.contains("https://")
                || lower.contains("jdbc:")
                || lower.contains("bucket")
                || lower.contains("object_key");
    }

    private boolean looksSensitiveOutputLine(String value) {
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
                || lower.contains("credential")
                || lower.contains("api_key")
                || lower.contains("apikey")
                || lower.contains("prompt:")
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

    private String safeText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record TextClipResult(String text, int bytes, boolean truncated) {
    }

    private record ControlCleanResult(String text, int replacedCount) {
    }

    private record SanitizedTextResult(String text,
                                       int sensitiveLineCount,
                                       int omittedLineCount,
                                       int controlCharacterCount) {
    }
}
