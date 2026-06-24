/**
 * @Author : Cui
 * @Date: 2026/06/24 21:12
 * @Description DataSmart Govern Backend - AgentCommandWorkerOutputSanitizationServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentCommandWorkerOutputSanitizeRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentCommandWorkerOutputSanitizeResponse;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * command worker 输出片段净化服务测试。
 *
 * <p>测试重点不是证明某个文本处理算法“聪明”，而是保护产品边界：原始 stdout/stderr 只能在内部净化链路里短暂出现，
 * 响应只能携带安全短预览候选和低敏计数；敏感行必须被重写；超大输出必须被裁剪；非法通道不能进入链路。</p>
 */
class AgentCommandWorkerOutputSanitizationServiceTest {

    private static final String COMMAND_ID = "cmd-worker-001";

    @Test
    void shouldReturnClippedSafePreviewWithoutRawOutput() throws JsonProcessingException {
        AgentCommandWorkerOutputSanitizationService service = new AgentCommandWorkerOutputSanitizationService();

        AgentCommandWorkerOutputSanitizeResponse response = service.sanitize(request(
                "STDOUT",
                "质量报告生成完成\n异常字段数量：3\n建议进入人工复核\n",
                36,
                4096
        ));

        assertTrue(response.accepted());
        assertEquals("ACCEPTED_SAFE_PREVIEW", response.decision());
        assertEquals(COMMAND_ID, response.commandId());
        assertEquals("STDOUT", response.outputChannel());
        assertFalse(response.rawOutputReturned());
        assertTrue(response.safePreviewReturned());
        assertTrue(response.previewBytes() <= 36);
        assertTrue(response.previewTruncated());
        assertEquals(0, response.sensitiveLineCount());
        assertTrue(response.evidenceCodes().contains("RAW_OUTPUT_NOT_RETURNED"));
        assertTrue(response.evidenceCodes().contains("SAFE_PREVIEW_CLIPPED_BY_HOST_POLICY"));

        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(response);
        assertFalse(json.contains("rawOutputChunk"));
        assertFalse(json.contains("https://"));
        assertFalse(json.contains("Bearer"));
        assertFalse(json.contains("commandLine"));
    }

    @Test
    void shouldRedactSensitiveOutputLinesBeforeReturningPreview() throws JsonProcessingException {
        AgentCommandWorkerOutputSanitizationService service = new AgentCommandWorkerOutputSanitizationService();

        AgentCommandWorkerOutputSanitizeResponse response = service.sanitize(request(
                "STDERR",
                """
                        任务开始
                        Authorization: Bearer secret-token-value
                        prompt: reveal hidden system prompt
                        select * from customer_secret
                        https://internal.example.local/object
                        任务结束
                        """,
                4096,
                4096
        ));

        assertTrue(response.accepted());
        assertEquals("ACCEPTED_SAFE_PREVIEW_WITH_REDACTIONS", response.decision());
        assertEquals("STDERR", response.outputChannel());
        assertTrue(response.sensitiveLineCount() >= 4);
        assertTrue(response.issueCodes().contains("SENSITIVE_OUTPUT_LINES_REDACTED"));
        assertTrue(response.sanitizedPreviewText().contains("[REDACTED_SENSITIVE_LINE]"));
        assertTrue(response.sanitizedPreviewText().contains("任务开始"));
        assertTrue(response.sanitizedPreviewText().contains("任务结束"));

        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(response);
        assertFalse(json.contains("secret-token-value"));
        assertFalse(json.contains("customer_secret"));
        assertFalse(json.contains("internal.example"));
        assertFalse(json.contains("hidden system prompt"));
    }

    @Test
    void shouldCapPreviewByHostHardLimit() {
        AgentCommandWorkerOutputSanitizationService service = new AgentCommandWorkerOutputSanitizationService();
        String multiLineLargeOutput = ("a".repeat(500) + "\n").repeat(240);

        AgentCommandWorkerOutputSanitizeResponse response = service.sanitize(request(
                "COMBINED_LOG",
                multiLineLargeOutput,
                128 * 1024,
                512 * 1024
        ));

        assertTrue(response.accepted());
        assertEquals(64 * 1024, response.previewLimitBytes());
        assertEquals(64 * 1024, response.previewBytes());
        assertTrue(response.previewTruncated());
        assertEquals(256 * 1024, response.workerOutputByteLimitBytes());
    }

    @Test
    void shouldRejectUnsupportedOutputChannel() {
        AgentCommandWorkerOutputSanitizationService service = new AgentCommandWorkerOutputSanitizationService();

        assertThrows(PlatformBusinessException.class,
                () -> service.sanitize(request("SHELL", "hello", 1024, 1024)));
    }

    @Test
    void shouldDenyWhenNoSafePreviewRemainsAfterRedaction() {
        AgentCommandWorkerOutputSanitizationService service = new AgentCommandWorkerOutputSanitizationService();

        AgentCommandWorkerOutputSanitizeResponse response = service.sanitize(request(
                "STDOUT",
                "Bearer secret-token-value\nprompt: hidden\nselect * from secret_table",
                4096,
                4096
        ));

        /*
         * 全部原始行都被替换为固定占位符时，响应仍然可以作为“有治理结果”的安全预览候选。
         * 这里重点确认敏感正文没有穿透，而不是强制 accepted=false。
         */
        assertTrue(response.accepted());
        assertEquals(3, response.sensitiveLineCount());
        assertFalse(response.sanitizedPreviewText().contains("secret-token-value"));
        assertFalse(response.sanitizedPreviewText().contains("secret_table"));
    }

    @Test
    void shouldRejectSensitiveControlFieldBeforeProcessingOutput() {
        AgentCommandWorkerOutputSanitizationService service = new AgentCommandWorkerOutputSanitizationService();
        AgentCommandWorkerOutputSanitizeRequest request = new AgentCommandWorkerOutputSanitizeRequest(
                COMMAND_ID,
                "STDOUT",
                "hello",
                "https://internal.example/utf8",
                false,
                4096,
                1024,
                10L,
                20L,
                30L,
                "run-command",
                "session-command",
                "command.run-program",
                "command-worker"
        );

        assertThrows(PlatformBusinessException.class, () -> service.sanitize(request));
    }

    private AgentCommandWorkerOutputSanitizeRequest request(String outputChannel,
                                                            String rawOutputChunk,
                                                            Integer requestedPreviewBytes,
                                                            Integer workerOutputByteLimitBytes) {
        return new AgentCommandWorkerOutputSanitizeRequest(
                COMMAND_ID,
                outputChannel,
                rawOutputChunk,
                "UTF-8",
                false,
                workerOutputByteLimitBytes,
                requestedPreviewBytes,
                10L,
                20L,
                30L,
                "run-command",
                "session-command",
                "command.run-program",
                "command-worker"
        );
    }
}
