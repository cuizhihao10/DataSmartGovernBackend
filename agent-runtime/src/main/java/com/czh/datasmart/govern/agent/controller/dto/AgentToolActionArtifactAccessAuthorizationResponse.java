/**
 * @Author : Cui
 * @Date: 2026/06/24 17:53
 * @Description DataSmart Govern Backend - AgentToolActionArtifactAccessAuthorizationResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * 命令执行产物访问预授权响应。
 *
 * <p>本响应的“授权”语义非常克制：它只表示调用方可以把某个低敏 artifactReference
 * 继续交给后续对象存储/制品服务做正文读取二次鉴权；它不代表当前接口已经返回了文件正文、
 * stdout/stderr、签名 URL 或 MinIO 内部对象键。商业化 Agent 平台里，工具输出经常同时具备
 * 排障价值和数据泄露风险，因此这里采用“先证明低敏引用归属，再由对象存储层单独放行正文”的双闸门模型。</p>
 *
 * @param authorized true 表示低敏引用与 command worker receipt 事实匹配，可进入下一段对象存储鉴权。
 * @param decision 机器可读决策码，用于前端、外部 Agent、审计系统和自动化测试稳定判断原因。
 * @param commandId 被校验的 commandId，不包含命令行正文。
 * @param artifactReference 被校验的低敏产物引用；该值仍然不是对象存储真实 URL 或正文。
 * @param artifactReferenceType 从匹配 receipt 中读取到的低敏引用类型。
 * @param requestedAccessMode 调用方请求的访问模式；当前一般为 METADATA_ONLY 或 BODY_READ。
 * @param metadataOnly true 表示当前响应只授权/返回元数据级信息。
 * @param bodyContentGranted true 才表示正文可读；当前阶段固定为 false，避免绕过后续存储层鉴权。
 * @param matchedReceiptPresent true 表示 runtime event 热窗口中找到匹配 command/artifact 的 worker receipt。
 * @param matchedReceiptFingerprint 匹配 receipt identityKey 的短摘要，便于排障串联但不暴露完整幂等键。
 * @param replaySequence Java 控制面分配的 replay 游标，用于前端或诊断页面定位事件顺序。
 * @param receiptOutcome worker receipt 中的低敏执行结果，例如 EXECUTION_SUCCEEDED 或 EXECUTION_FAILED。
 * @param tenantId 匹配 receipt 的租户 ID，来自 runtime event 低敏字段。
 * @param projectId 匹配 receipt 的项目 ID，来自 runtime event 低敏字段。
 * @param actorId 匹配 receipt 的触发 actor ID，来自 runtime event 低敏字段。
 * @param runId 匹配 receipt 的 runId，来自 runtime event 路由维度。
 * @param sessionId 匹配 receipt 的 sessionId，来自 runtime event 路由维度。
 * @param toolCode 匹配 receipt 的工具编码，不包含工具参数。
 * @param evidenceCodes 支持授权的低敏证据码，避免返回任何敏感正文。
 * @param issueCodes 拒绝或降级原因码，便于调用方选择重试、补授权或人工介入。
 * @param recommendedActions 下一步建议，例如补对象存储授权、等待 receipt 落盘或转人工复核。
 * @param payloadPolicy 当前接口承诺的低敏载荷策略。
 */
public record AgentToolActionArtifactAccessAuthorizationResponse(
        boolean authorized,
        String decision,
        String commandId,
        String artifactReference,
        String artifactReferenceType,
        String requestedAccessMode,
        boolean metadataOnly,
        boolean bodyContentGranted,
        boolean matchedReceiptPresent,
        String matchedReceiptFingerprint,
        Long replaySequence,
        String receiptOutcome,
        String tenantId,
        String projectId,
        String actorId,
        String runId,
        String sessionId,
        String toolCode,
        List<String> evidenceCodes,
        List<String> issueCodes,
        List<String> recommendedActions,
        String payloadPolicy
) {
}
