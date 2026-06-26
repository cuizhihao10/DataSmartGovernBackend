/**
 * @Author : Cui
 * @Date: 2026/06/26 00:00
 * @Description DataSmart Govern Backend - AgentCommandTaskFinalStateDecision.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import java.util.List;

/**
 * 命令任务最终态对账的内部决策模型。
 *
 * <p>该 record 不直接暴露给 HTTP 调用方，而是把“receipt outcome -> task 状态建议”的业务规则结果
 * 从查询服务中拆出来。这样拆分有两个目的：</p>
 * <p>1. 保持 Service 文件长度和职责可控，避免所有规则都堆到一个巨大的 Impl 中；</p>
 * <p>2. 未来 task-management 增加 PARTIALLY_SUCCEEDED、CANCELLED、COMPENSATING 等状态时，
 *    可以只扩展决策解析器，不影响 Controller DTO 和权限查询流程。</p>
 *
 * @param reconciliationStatus 对账结论，例如 SUCCEEDED、FAILED、WAITING_WORKER、BLOCKED_BEFORE_EXECUTION。
 * @param reconciledTaskStatus 建议 task/Agent 审计进入的状态，例如 RUNNING、DEFERRED、SUCCEEDED、FAILED。
 * @param terminal true 表示建议状态为终态。
 * @param callbackRecommended true 表示当前事实足以触发状态回调或可见性刷新。
 * @param requiresManualCompensation true 表示副作用失败或补偿风险需要人工确认。
 * @param retryable true 表示当前不是终态，后续可以等待队列、容量或 worker 重试。
 * @param callbackStatus 建议映射到 AgentAsyncToolTaskStatusCallbackRequest.status 的状态。
 * @param callbackMessage 低敏回调说明。
 * @param callbackErrorCode 建议失败码。
 * @param outputSummary 建议成功摘要。
 * @param evidenceCodes 支撑结论的低敏证据码。
 * @param issueCodes 当前仍需关注的问题码。
 * @param recommendedActions 建议下一步动作。
 */
record AgentCommandTaskFinalStateDecision(
        String reconciliationStatus,
        String reconciledTaskStatus,
        Boolean terminal,
        Boolean callbackRecommended,
        Boolean requiresManualCompensation,
        Boolean retryable,
        String callbackStatus,
        String callbackMessage,
        String callbackErrorCode,
        String outputSummary,
        List<String> evidenceCodes,
        List<String> issueCodes,
        List<String> recommendedActions
) {

    static AgentCommandTaskFinalStateDecision waiting(String status,
                                                      String message,
                                                      List<String> evidenceCodes,
                                                      List<String> issueCodes,
                                                      List<String> recommendedActions) {
        return new AgentCommandTaskFinalStateDecision(
                status,
                "RUNNING",
                false,
                false,
                false,
                true,
                null,
                message,
                null,
                null,
                List.copyOf(evidenceCodes),
                List.copyOf(issueCodes),
                List.copyOf(recommendedActions)
        );
    }
}
