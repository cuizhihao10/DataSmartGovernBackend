/**
 * @Author : Cui
 * @Date: 2026/06/20 16:51
 * @Description DataSmart Govern Backend - DataSyncWorkerOutboxDiagnosticsResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.datasync;

import java.util.List;
import java.util.Map;

/**
 * DataSync worker outbox 诊断结果。
 *
 * <p>诊断结果分成两层：</p>
 * <p>1. counts：按 outbox 状态聚合，方便运维快速判断是“没有领取”“正在投递”“等待重试”还是“需要人工处理”；</p>
 * <p>2. recentRecords：最近记录的低敏列表，方便从 taskId/commandId/outboxId 继续定位到更完整的受控审计链路。</p>
 *
 * @param schemaVersion 响应契约版本。
 * @param requestedStatus 请求中的状态过滤条件，null 表示不过滤状态。
 * @param totalCount 满足租户、项目、task、command 和 requestedStatus 条件的总数。
 * @param statusCounts 在相同租户、项目、task、command 条件下，各状态的数量。
 * @param recentRecords 最近 outbox 低敏记录。
 * @param lowSensitivePolicy 本接口的数据脱敏和不外露策略说明。
 * @param warnings 低敏诊断提示，例如存在失败或死信命令。
 */
public record DataSyncWorkerOutboxDiagnosticsResult(
        String schemaVersion,
        String requestedStatus,
        long totalCount,
        Map<String, Long> statusCounts,
        List<DataSyncWorkerCommandOutboxView> recentRecords,
        String lowSensitivePolicy,
        List<String> warnings
) {
}
