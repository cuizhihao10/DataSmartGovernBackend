/**
 * @Author : Cui
 * @Date: 2026/06/24 21:09
 * @Description DataSmart Govern Backend - AgentCommandWorkerOutputSanitizeResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * 受控命令 worker 输出片段净化响应。
 *
 * <p>响应只返回“可作为 artifact 预览候选的安全短文本”和低敏治理元数据，不返回原始 stdout/stderr。
 * 后续对象存储服务如果要把该预览展示给前端或 Agent，还必须继续调用 body-read-final-checks 做 grant 回查和
 * 最后一跳裁剪。这样形成 worker 输出治理的链式闭环：</p>
 *
 * <p>`raw worker output -> output-sanitizations -> artifact metadata/body-read grant -> body-read-final-checks`。</p>
 *
 * @param accepted true 表示请求完成净化处理；false 表示没有可返回的安全预览候选。
 * @param decision 机器可读决策码，便于 worker、测试和运维台稳定判断处理结果。
 * @param commandId 被净化输出所属命令 ID。
 * @param outputChannel 归一化后的输出通道码，例如 STDOUT、STDERR 或 COMBINED_LOG。
 * @param rawOutputReturned 当前响应是否返回原始输出；本阶段固定为 false。
 * @param safePreviewReturned true 表示响应包含可继续进入 final-check 的安全预览候选。
 * @param sanitizedPreviewText 已按敏感行重写和 UTF-8 字节预算裁剪后的短文本；不是完整 stdout/stderr。
 * @param previewLimitBytes 服务端本次采用的安全预览字节上限。
 * @param previewBytes 返回预览的 UTF-8 字节数。
 * @param previewTruncated true 表示候选文本被服务端按字节上限裁剪。
 * @param rawInputBytes 请求中原始输出片段的 UTF-8 字节数，仅用于预算和排障，不代表响应返回了原文。
 * @param processedInputBytes 服务端实际进入净化流程的输入字节数，超过硬上限时会小于 rawInputBytes。
 * @param workerOutputByteLimitBytes worker 声明的输出预算，服务端会把它纳入最终 previewLimit 计算。
 * @param rawOutputTruncatedByWorker true 表示 worker 在本地已经裁剪过输出。
 * @param sensitiveLineCount 被识别为敏感并替换为占位符的行数。
 * @param omittedLineCount 因行数或字节预算被省略的行数。
 * @param controlCharacterCount 被替换掉的控制字符数量。
 * @param candidateContentType 建议对象存储或 artifact 服务登记的内容类型。
 * @param evidenceCodes 支持净化结果的低敏证据码。
 * @param issueCodes 净化过程中发现的问题码，例如敏感行重写、输入过大或无安全预览。
 * @param recommendedActions 下一步建议，例如写入 artifactReference、调用 final-check 或进入补偿。
 * @param payloadPolicy 当前响应承诺的低敏载荷策略。
 */
public record AgentCommandWorkerOutputSanitizeResponse(
        boolean accepted,
        String decision,
        String commandId,
        String outputChannel,
        boolean rawOutputReturned,
        boolean safePreviewReturned,
        String sanitizedPreviewText,
        Integer previewLimitBytes,
        Integer previewBytes,
        boolean previewTruncated,
        Integer rawInputBytes,
        Integer processedInputBytes,
        Integer workerOutputByteLimitBytes,
        boolean rawOutputTruncatedByWorker,
        Integer sensitiveLineCount,
        Integer omittedLineCount,
        Integer controlCharacterCount,
        String candidateContentType,
        List<String> evidenceCodes,
        List<String> issueCodes,
        List<String> recommendedActions,
        String payloadPolicy
) {
}
