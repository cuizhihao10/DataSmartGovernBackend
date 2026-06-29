/**
 * @Author : Cui
 * @Date: 2026/06/29 00:00
 * @Description DataSmart Govern Backend - AgentWorkspaceFilePayloadMaterializationResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import com.czh.datasmart.govern.agent.service.runtime.AgentWorkspaceFilePayloadMaterializationService;

import java.util.List;

/**
 * Workspace 文件工具 payload 物化响应。
 *
 * <p>该响应是“可以进入 API、日志摘要、事件投影和管理台”的低敏视图。它故意不包含 relativePath、
 * content、contentReference 原值、workspace root、SQL、prompt、工具参数正文、凭据或内部 endpoint。
 * 如果后续 worker 需要真实参数，只能拿 `payloadReference` 在服务端内部回查 payload store。</p>
 *
 * @param materialized 是否已经成功写入服务端 payload store。
 * @param materializationState 物化状态，成功时表示 worker 可在服务端内部读取。
 * @param payloadReference 受控 `agent-payload:` 引用，可进入 outbox 和审计。
 * @param toolName 工具编码，不包含参数值。
 * @param operation READ/WRITE 操作。
 * @param payloadBodyAvailable store 内部是否已有真实 body。
 * @param payloadSizeBytes 内部 body 字节数，只用于容量治理和排障。
 * @param pathDigest 相对路径摘要，不能反推路径明文。
 * @param contentSha256 写入正文摘要；不包含正文。
 * @param contentSizeBytes 写入正文大小；不包含正文。
 * @param contentReferenceProvided 是否提供正文引用；不回显引用值。
 * @param overwrite 覆盖策略摘要。
 * @param expectedSha256Provided 是否提供乐观并发校验值；不回显校验值。
 * @param payloadPolicy 当前 payload 低敏治理策略。
 * @param evidenceCodes 低敏证据码。
 * @param issueCodes 低敏问题码。
 * @param recommendedActions 后续建议动作。
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

    /**
     * 将领域服务响应转换成 Controller DTO。
     *
     * <p>这里不做任何字段扩展，尤其不能把 service 内部 payload body、relativePath 或 content 拼进响应。
     * 将转换逻辑放在 DTO 静态方法中，可以让 Controller 保持薄层，也让单元测试明确保护“响应白名单”。</p>
     */
    public static AgentWorkspaceFilePayloadMaterializationResponse from(
            AgentWorkspaceFilePayloadMaterializationService.AgentWorkspaceFilePayloadMaterializationResponse response) {
        return new AgentWorkspaceFilePayloadMaterializationResponse(
                response.materialized(),
                response.materializationState(),
                response.payloadReference(),
                response.toolName(),
                response.operation(),
                response.payloadBodyAvailable(),
                response.payloadSizeBytes(),
                response.pathDigest(),
                response.contentSha256(),
                response.contentSizeBytes(),
                response.contentReferenceProvided(),
                response.overwrite(),
                response.expectedSha256Provided(),
                response.payloadPolicy(),
                response.evidenceCodes(),
                response.issueCodes(),
                response.recommendedActions()
        );
    }
}
