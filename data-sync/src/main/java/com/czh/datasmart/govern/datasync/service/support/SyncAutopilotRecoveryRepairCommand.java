/**
 * @Author : Cui
 * @Date: 2026/08/14
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryRepairCommand.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.support.SyncAutopilotRecoveryAction;

import java.util.Map;

/**
 * 已完成 HTTP 枚举解析的受治理修复命令。
 *
 * <p>命令仍然不是执行授权。服务层必须重新验证 case 状态、双主体、任务范围、策略摘要、循环预算、
 * 动作参数和跨语言指纹，全部通过后才能产生副作用。</p>
 */
public record SyncAutopilotRecoveryRepairCommand(
        Long caseId,
        Long expectedVersion,
        Long tenantId,
        Long projectId,
        Long syncTaskId,
        Long executionId,
        Integer cycle,
        String authorizationDigest,
        String policyDigest,
        SyncAutopilotRecoveryAction action,
        String actionFingerprint,
        String receiptId,
        Map<String, Object> repairParameters) {
}
