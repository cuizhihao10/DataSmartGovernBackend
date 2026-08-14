/**
 * @Author : Cui
 * @Date: 2026/08/14
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryRepairRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import java.util.Map;

/**
 * Autopilot 在首次授权盒内申请执行一个固定低风险修复的内部请求。
 *
 * <p>该 DTO 只承载范围、乐观锁、摘要、动作指纹和白名单参数。它不允许提交 SQL、完整任务定义、
 * checkpoint 值、字段值、连接信息或任意工具地址。真正使用的策略快照、checkpoint、失败分片和
 * 元数据都由 data-sync 从权威存储重新读取。</p>
 */
public record SyncAutopilotRecoveryRepairRequest(
        Long expectedVersion,
        Long tenantId,
        Long projectId,
        Long syncTaskId,
        Long executionId,
        Integer cycle,
        String authorizationDigest,
        String policyDigest,
        String action,
        String actionFingerprint,
        String receiptId,
        Map<String, Object> repairParameters) {
}
