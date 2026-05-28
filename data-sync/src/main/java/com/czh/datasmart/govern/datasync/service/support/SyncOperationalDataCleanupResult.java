/**
 * @Author : Cui
 * @Date: 2026/05/08 23:43
 * @Description DataSmart Govern Backend - SyncOperationalDataCleanupResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import java.time.LocalDateTime;

/**
 * data-sync 运行数据清理结果。
 *
 * <p>一次运行数据清理会覆盖多张表，因此需要用一个结果对象统一记录：
 * 1. 每类数据使用的过期边界；
 * 2. 每类数据实际删除数量；
 * 3. 单表删除批次上限。
 *
 * <p>这样 scheduler、metrics 和日志都读取同一个结果对象，避免不同组件分别计算导致口径不一致。
 */
public record SyncOperationalDataCleanupResult(
        LocalDateTime checkpointExpireBefore,
        LocalDateTime errorSampleExpireBefore,
        LocalDateTime auditRecordExpireBefore,
        LocalDateTime closedIncidentExpireBefore,
        int requestedLimitPerTable,
        int deletedCheckpoints,
        int deletedErrorSamples,
        int deletedAuditRecords,
        int deletedClosedIncidents
) {

    /**
     * 本轮总删除数量。
     */
    public int totalDeleted() {
        return deletedCheckpoints + deletedErrorSamples + deletedAuditRecords + deletedClosedIncidents;
    }
}
