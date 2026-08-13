/**
 * @Author : Cui
 * @Date: 2026/08/10 16:10
 * @Description DataSmart Govern Backend - AgentAutopilotAuthorizationSnapshot.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.autopilot;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 持久化到 Run 和同步任务定义中的低敏 Autopilot 授权快照。 */
public record AgentAutopilotAuthorizationSnapshot(
        String policyId,
        String policyVersion,
        String state,
        String rootSessionId,
        String rootRunId,
        Long tenantId,
        Long applicationId,
        Long projectId,
        String userId,
        String actorId,
        String agentId,
        String delegationId,
        int maxRecoveryCycles,
        int maxTotalDurationMinutes,
        String maxAutomaticRiskLevel,
        List<String> allowedRecoveryActions,
        List<String> requireApprovalFor,
        OffsetDateTime issuedAt,
        OffsetDateTime expiresAt,
        String policyDigest) {

    /**
     * 创建授权记录时复制两个动作列表，防止调用方在授权建立后修改其权限内容。
     *
     * <p>输入的列表只在构造时读取，输出 record 持有不可修改副本；该操作没有数据库、网络或审批副作用。
     * 复制是完整性保护而不是授权校验，调用方仍必须先由 {@link AgentAutopilotAuthorizationService} 验证白名单。
     * 列表顺序会保留并参与后续授权摘要材料，使同一已验证内容可被幂等地序列化和比较。</p>
     *
     * @throws NullPointerException 当任一动作列表为 {@code null} 时，拒绝生成无法安全验证的快照
     */
    public AgentAutopilotAuthorizationSnapshot {
        allowedRecoveryActions = List.copyOf(allowedRecoveryActions);
        requireApprovalFor = List.copyOf(requireApprovalFor);
    }

    /**
     * 将当前授权事实展开为供 Jackson 和 PostgreSQL JSONB 持久化使用的低敏字段映射。
     *
     * <p>没有调用方输入，输出包含范围、动作、时限和 {@code policyDigest}，但不生成新权限、不重新计算摘要，
     * 也不写库。持久化方必须把返回值与对应 run 一起保存，供 Kafka 恢复时作为权限和完整性证据使用。
     * 返回值为不可修改 Map，因此同一快照重复调用不会累积状态或改变幂等比较结果。</p>
     *
     * @return 不可修改的授权字段映射
     * @throws NullPointerException 当快照中本应由授权服务保证的必填字段缺失，无法安全序列化时
     */
    public Map<String, Object> toMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("policyId", policyId);
        value.put("policyVersion", policyVersion);
        value.put("executionMode", "AUTOPILOT");
        value.put("state", state);
        value.put("rootSessionId", rootSessionId);
        value.put("rootRunId", rootRunId);
        value.put("tenantId", tenantId);
        value.put("applicationId", applicationId);
        value.put("projectId", projectId);
        value.put("userId", userId);
        value.put("actorId", actorId);
        value.put("agentId", agentId);
        value.put("delegationId", delegationId);
        value.put("maxRecoveryCycles", maxRecoveryCycles);
        value.put("maxTotalDurationMinutes", maxTotalDurationMinutes);
        value.put("maxAutomaticRiskLevel", maxAutomaticRiskLevel);
        value.put("allowedRecoveryActions", allowedRecoveryActions);
        value.put("requireApprovalFor", requireApprovalFor);
        value.put("issuedAt", issuedAt.toString());
        value.put("expiresAt", expiresAt.toString());
        value.put("policyDigest", policyDigest);
        return Map.copyOf(value);
    }
}
