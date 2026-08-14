/**
 * @Author : Cui
 * @Date: 2026/08/14
 * @Description DataSmart Govern Backend - AgentAutopilotRecoveryRepairReceipt.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.autopilot;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * data-sync 返回的受治理修复回执。
 *
 * <p>{@code applied=true} 只表示配置修复或重新排队已在控制面持久提交，不表示同步任务已经成功。
 * Agent Runtime 仍需推进 recovery case，并调用 PRECHECK_AGENT 与 MONITOR_AGENT 完成恢复后验证。</p>
 */
public record AgentAutopilotRecoveryRepairReceipt(
        String receiptId,
        Long caseId,
        Long syncTaskId,
        Long sourceExecutionId,
        Long executionId,
        String action,
        boolean applied,
        int affectedCount,
        String executionState,
        String taskState,
        String reasonCode,
        List<String> issueCodes,
        String actionFingerprint,
        String caseState,
        boolean replanQueued,
        String replanEventId,
        Integer nextCycle) {

    public AgentAutopilotRecoveryRepairReceipt {
        issueCodes = issueCodes == null ? List.of() : List.copyOf(issueCodes);
    }

    /** 判断下游是否明确确认了修复副作用，而不是只返回 HTTP 成功。 */
    public boolean isDurablyApplied() {
        return applied && affectedCount > 0 && executionId != null && executionId > 0
                && !blank(executionState) && !blank(taskState) && !blank(reasonCode)
                && "AUTO_APPROVED".equals(code(caseState))
                && !replanQueued && blank(replanEventId) && nextCycle == null;
    }

    /**
     * 判断 data-sync 是否已经用状态机回执收敛未应用动作，从而避免 Agent Runtime 再次写入同一失败边。
     *
     * <p>如果还有循环预算，回执必须携带下一轮持久事件 ID 和严格递增一的轮次；若预算耗尽，允许没有
     * 下一轮事件，但 case 仍必须处于 ATTENTION_REQUIRED。该判断只验证控制面收敛事实，不把它误报为
     * 修复成功，也不会自行触发模型或 Kafka。</p>
     *
     * @param event 当前已验证的恢复触发事件
     * @return data-sync 是否已经完整承担了旧 case 收敛职责
     */
    public boolean isDurablyConvergedNotApplied(AgentAutopilotRecoveryTriggerEvent event) {
        if (applied || affectedCount != 0 || event == null
                || !"ATTENTION_REQUIRED".equals(code(caseState))
                || blank(reasonCode) || nextCycle == null || nextCycle != event.cycle() + 1) {
            return false;
        }
        if (replanQueued) {
            return nextCycle <= event.maxRecoveryCycles()
                    && replanEventId != null
                    && replanEventId.matches("autopilot-trigger:[0-9a-f]{64}");
        }
        return blank(replanEventId);
    }

    /**
     * 将回执与原事件、case 和候选逐字段绑定，防止跨任务或跨动作响应进入后续验证。
     *
     * <p>checkpoint 恢复会创建新 execution，因此只要求新标识为正且与源 execution 不同；其余修复
     * 必须继续作用于原 execution。即使 {@code applied=false}，身份和指纹也必须匹配，才能把它当作
     * 当前动作的确定性拒绝结果。</p>
     */
    public boolean matchesScope(
            AgentAutopilotRecoveryTriggerEvent event,
            AgentAutopilotRecoveryCaseView recoveryCase,
            AgentAutopilotRecoveryPlanResponse response) {
        if (event == null || recoveryCase == null || response == null) {
            return false;
        }
        String expectedReceiptId = event.eventId() + ":repair-apply";
        String expectedAction = code(response.action());
        boolean executionMatches = "RESUME_FROM_CHECKPOINT".equals(expectedAction)
                ? executionId != null && executionId > 0 && !Objects.equals(executionId, sourceExecutionId)
                : Objects.equals(executionId, event.currentExecutionId());
        return Objects.equals(receiptId, expectedReceiptId)
                && Objects.equals(caseId, recoveryCase.caseId())
                && Objects.equals(syncTaskId, event.syncTaskId())
                && Objects.equals(sourceExecutionId, event.currentExecutionId())
                && executionMatches
                && Objects.equals(code(action), expectedAction)
                && Objects.equals(actionFingerprint, response.repairFingerprint());
    }

    private static String code(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
