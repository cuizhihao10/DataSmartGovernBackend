/**
 * @Author : Cui
 * @Date: 2026/06/28 23:28
 * @Description DataSmart Govern Backend - SyncConnectorCompatibilityView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import java.util.List;

/**
 * 连接器与同步模式兼容性诊断视图。
 *
 * <p>该视图回答“某个源连接器、目标连接器、同步模式组合，在当前产品能力矩阵下是否建议放行”。
 * 它不是执行结果，也不代表真实数据源已经连通。真实执行前仍需要 datasource-management 连接测试、权限校验、
 * 模板配置校验、任务状态机、执行器 lease 和 checkpoint 回调共同确认。</p>
 *
 * @param sourceConnectorType 源端连接器类型。
 * @param targetConnectorType 目标端连接器类型。
 * @param syncMode 同步模式。
 * @param supported 当前组合是否满足连接器能力矩阵。
 * @param consistencyGoal 推荐一致性目标，例如 SNAPSHOT_BOUNDED、AT_LEAST_ONCE_DEDUP_AWARE。
 * @param checkpointRequired 该模式是否建议强制 checkpoint。
 * @param retryPattern 推荐重试模式，例如 SEGMENT_RETRY、WINDOW_RETRY、OFFSET_RECOVERY。
 * @param issueCodes 不兼容或需要关注的问题编码。
 * @param recommendedActions 推荐下一步动作，例如补 fieldMapping、开启 checkpoint、降低并发。
 * @param payloadPolicy 低敏载荷策略，固定提醒调用方不要把连接串、SQL 或样本数据放入诊断。
 * @param performanceNotes 性能提示。
 * @param safetyNotes 安全与治理提示。
 */
public record SyncConnectorCompatibilityView(
        String sourceConnectorType,
        String targetConnectorType,
        String syncMode,
        boolean supported,
        String consistencyGoal,
        boolean checkpointRequired,
        String retryPattern,
        List<String> issueCodes,
        List<String> recommendedActions,
        String payloadPolicy,
        List<String> performanceNotes,
        List<String> safetyNotes
) {
}
