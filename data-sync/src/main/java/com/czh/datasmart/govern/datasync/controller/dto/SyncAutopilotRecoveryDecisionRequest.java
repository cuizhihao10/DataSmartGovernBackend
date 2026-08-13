/**
 * @Author : Cui
 * @Date: 2026/08/11 19:35
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryDecisionRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import java.time.OffsetDateTime;

/**
 * agent-runtime 提交给 data-sync 的低敏 Autopilot 决策请求。
 *
 * <p>请求不包含 prompt、日志正文、RAG 文档、SQL 或凭据。data-sync 不采信 Java 已经给出的最终
 * “允许执行”结论，而是使用任务定义中持久化的授权快照再次计算策略，形成双重治理边界。</p>
 *
 * <p>这是内部 HTTP 的反序列化合同，不是浏览器授权对象。{@code receiptId} 将全部决策事实绑定为一次
 * 可安全重放的调用；相同事实的重试返回原有案例，而改变事实后复用该 ID 会被拒绝。DTO 自身没有校验、
 * 持久化或状态推进副作用，控制器只做最小形状检查，服务层再重新校验任务、执行、租户/项目和策略。</p>
 */
public record SyncAutopilotRecoveryDecisionRequest(
        Long tenantId,
        Long projectId,
        Long syncTaskId,
        Long rootExecutionId,
        Long currentExecutionId,
        int cycle,
        OffsetDateTime deadlineAt,
        String errorFingerprint,
        int repeatedErrorCount,
        String action,
        String riskLevel,
        String repairFingerprint,
        String receiptId,
        int confidenceScore,
        boolean evidenceAvailable) {
}
