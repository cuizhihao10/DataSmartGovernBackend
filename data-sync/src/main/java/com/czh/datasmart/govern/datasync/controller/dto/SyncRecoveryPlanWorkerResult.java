/**
 * @Author : Cui
 * @Date: 2026/06/27 16:20
 * @Description DataSmart Govern Backend - SyncRecoveryPlanWorkerResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncExecutionRecoveryPlan;

import java.util.Locale;
import java.util.Set;

/**
 * worker 恢复计划读取/消费结果。
 *
 * <p>该 DTO 是 data-sync 暴露给 worker 的恢复执行契约，字段采用“低敏白名单”设计。
 * 它只返回 replay/backfill 需要的控制坐标，例如来源 execution、checkpoint、补数窗口和分区选择器；
 * 不返回 SQL、连接串、用户名密码、token、样本行、错误样本、模型输出、工具参数或内部服务地址。
 *
 * <p>字段说明：
 * @param hasRecoveryPlan 当前 execution 是否绑定恢复计划。false 表示这是普通 MANUAL/SCHEDULED/API execution，
 *                        worker 可以按普通同步路径继续，不需要加载 replay/backfill 特殊策略。
 * @param tenantId 租户 ID，用于 worker 本地日志、指标和租户级隔离；不代表 worker 可跨租户查询更多数据。
 * @param projectId 项目 ID，用于项目级日志归档和资源配额统计。
 * @param workspaceId 工作空间 ID，用于未来 workspace 沙箱、文件输出和资源配额。
 * @param syncTaskId 同步任务 ID，用于把恢复执行和任务配置关联起来。
 * @param executionId 当前被 worker 认领的 executionId。
 * @param recoveryPlanId 恢复计划主键，便于 worker 日志和后续审计定位。
 * @param recoveryType 恢复类型：REPLAY 或 BACKFILL。
 * @param sourceExecutionId replay 来源 executionId；backfill 通常为空。
 * @param sourceCheckpointId replay 来源 checkpointId；为空表示从来源 execution 可解释的起点回放。
 * @param windowStart backfill 窗口开始边界，低敏字符串。
 * @param windowEnd backfill 窗口结束边界，低敏字符串。
 * @param shardOrPartition backfill 分片或分区选择器，不能存放 SQL 片段或完整 WHERE 条件。
 * @param reason 低敏操作原因摘要，主要用于 worker 日志，不应包含业务样本和敏感参数。
 * @param planState 返回给 worker 的计划状态。
 * @param message 低敏人类可读说明，用于 worker 日志和排障。
 */
public record SyncRecoveryPlanWorkerResult(
        boolean hasRecoveryPlan,
        Long tenantId,
        Long projectId,
        Long workspaceId,
        Long syncTaskId,
        Long executionId,
        Long recoveryPlanId,
        String recoveryType,
        Long sourceExecutionId,
        Long sourceCheckpointId,
        String windowStart,
        String windowEnd,
        String shardOrPartition,
        String reason,
        String planState,
        String message
) {

    /**
     * 构造“普通 execution，无恢复计划”的响应。
     *
     * <p>worker 可以在 claim 后统一调用 recovery-plan/claim。如果返回 hasRecoveryPlan=false，
     * 就说明当前 execution 没有 replay/backfill 特殊恢复契约，按普通同步读取、写入和 checkpoint 流程执行即可。
     */
    public static SyncRecoveryPlanWorkerResult noPlan(SyncExecution execution, String message) {
        return new SyncRecoveryPlanWorkerResult(
                false,
                execution.getTenantId(),
                execution.getProjectId(),
                execution.getWorkspaceId(),
                execution.getSyncTaskId(),
                execution.getId(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                message
        );
    }

    /**
     * 从持久化恢复计划映射为低敏 worker 契约。
     *
     * <p>这里集中做字段白名单映射，是为了防止未来 {@link SyncExecutionRecoveryPlan} 新增字段时被接口自动暴露。
     * 如果后续需要返回更多字段，应先评估 worker 决策是否真的需要，以及字段是否可能泄露业务数据。
     */
    public static SyncRecoveryPlanWorkerResult fromPlan(SyncExecutionRecoveryPlan plan, String message) {
        return new SyncRecoveryPlanWorkerResult(
                true,
                plan.getTenantId(),
                plan.getProjectId(),
                plan.getWorkspaceId(),
                plan.getSyncTaskId(),
                plan.getExecutionId(),
                plan.getId(),
                plan.getRecoveryType(),
                plan.getSourceExecutionId(),
                plan.getSourceCheckpointId(),
                safeLowSensitiveText(plan.getWindowStart(), 128),
                safeLowSensitiveText(plan.getWindowEnd(), 128),
                safeLowSensitiveText(plan.getShardOrPartition(), 256),
                safeLowSensitiveText(plan.getReason(), 300),
                plan.getPlanState(),
                message
        );
    }

    /**
     * 对 worker 响应中的自由文本做最后一道低敏防护。
     *
     * <p>恢复计划创建入口已经会尽量阻止敏感内容写入，但 worker 协议是更靠近执行面的接口，
     * 因此这里再做一次白名单式防护：如果文本里出现明显 SQL、凭据、token、连接串、prompt 或样本关键词，
     * 就不把原文返回给 worker。这样即使早期数据或人工误填进入了数据库，也不会继续通过机器协议扩散。
     */
    private static String safeLowSensitiveText(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String compact = value.trim().replaceAll("\\s+", " ");
        if (containsSensitiveKeyword(compact)) {
            return "该字段包含潜在敏感内容，已在 worker 响应中脱敏";
        }
        if (compact.length() <= maxLength) {
            return compact;
        }
        return compact.substring(0, maxLength);
    }

    private static boolean containsSensitiveKeyword(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        Set<String> keywords = Set.of(
                "password", "passwd", "token", "secret", "credential", "access_key", "private_key",
                "jdbc:", "select ", "insert ", "update ", "delete ", "where ", " sql", "prompt", "payload", "sample",
                "密码", "密钥", "令牌", "凭据", "样本", "连接串"
        );
        for (String keyword : keywords) {
            if (lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
