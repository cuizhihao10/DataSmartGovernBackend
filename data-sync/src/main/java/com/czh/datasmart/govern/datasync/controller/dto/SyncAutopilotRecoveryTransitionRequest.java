/**
 * @Author : Cui
 * @Date: 2026/08/11 19:35
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryTransitionRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

/**
 * agent-runtime 推进 recovery case 生命周期时使用的乐观锁请求。
 *
 * <p>``expectedVersion`` 防止两个 Agent 实例同时推进同一案例；``receiptId`` 负责调用幂等。
 * 当前执行 ID、循环次数和错误指纹只在确有新事实时传入，空值表示沿用已持久化值。</p>
 *
 * <p>该 DTO 不能指定目标状态，{@code receiptType} 只是要求状态机验证的一条业务事实。它不包含策略正文、
 * 原始错误、SQL、凭据或模型输出，也不会自行写数据库。控制器验证内部服务身份后，服务层仍会检查案例归属、
 * receipt 摘要、当前状态和条件更新版本，因此重复回调可重放，过期或篡改的回调不能推进状态。</p>
 */
public record SyncAutopilotRecoveryTransitionRequest(
        Long expectedVersion,
        String receiptId,
        String receiptType,
        Long currentExecutionId,
        Integer cycle,
        String errorFingerprint,
        Integer repeatedErrorCount,
        String attentionReason) {
}
