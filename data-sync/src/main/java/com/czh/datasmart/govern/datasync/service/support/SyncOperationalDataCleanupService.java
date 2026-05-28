/**
 * @Author : Cui
 * @Date: 2026/05/08 23:44
 * @Description DataSmart Govern Backend - SyncOperationalDataCleanupService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.config.DataSyncMaintenanceProperties;
import com.czh.datasmart.govern.datasync.mapper.SyncAuditRecordMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncCheckpointMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncErrorSampleMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncIncidentRecordMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * data-sync 运行数据保留期清理服务。
 *
 * <p>这个类承担“按不同业务数据类型执行保留期策略”的职责：
 * 1. checkpoint 用于恢复，保留窗口通常较短；
 * 2. error sample 用于排障和数据修复，保留窗口通常中等；
 * 3. audit record 用于合规追溯，保留窗口通常最长；
 * 4. incident record 用于运营闭环，只清理 CLOSED 且关闭时间超过保留期的记录。
 *
 * <p>为什么不把这些逻辑写在 scheduler 里：
 * scheduler 应该只表达“什么时候触发”和“如何避免重入”，不应该承载不同表的保留期规则。
 * 后续如果增加归档表、对象存储导出或租户级差异化策略，也应该从这里扩展，而不是改调度器。
 */
@Component
@RequiredArgsConstructor
public class SyncOperationalDataCleanupService {

    private final DataSyncMaintenanceProperties maintenanceProperties;
    private final SyncCheckpointMapper checkpointMapper;
    private final SyncErrorSampleMapper errorSampleMapper;
    private final SyncAuditRecordMapper auditRecordMapper;
    private final SyncIncidentRecordMapper incidentRecordMapper;

    /**
     * 执行一轮运行数据保留期清理。
     *
     * <p>每张表独立计算过期边界并独立删除。这样做的好处是：
     * 1. 不同数据类型可以拥有不同保留期；
     * 2. 某张表没有历史数据时，不影响其他表清理；
     * 3. 指标可以清楚展示是哪类数据增长过快或清理积压。
     *
     * @return 本轮四类运行数据的清理结果
     */
    public SyncOperationalDataCleanupResult cleanupExpiredOperationalData() {
        int limit = maintenanceProperties.effectiveOperationalCleanupBatchSize();
        LocalDateTime checkpointExpireBefore = LocalDateTime.now()
                .minusDays(maintenanceProperties.effectiveCheckpointRetentionDays());
        LocalDateTime errorSampleExpireBefore = LocalDateTime.now()
                .minusDays(maintenanceProperties.effectiveErrorSampleRetentionDays());
        LocalDateTime auditRecordExpireBefore = LocalDateTime.now()
                .minusDays(maintenanceProperties.effectiveAuditRecordRetentionDays());
        LocalDateTime closedIncidentExpireBefore = LocalDateTime.now()
                .minusDays(maintenanceProperties.effectiveClosedIncidentRetentionDays());

        int deletedCheckpoints = checkpointMapper.deleteExpiredCheckpoints(checkpointExpireBefore, limit);
        int deletedErrorSamples = errorSampleMapper.deleteExpiredErrorSamples(errorSampleExpireBefore, limit);
        int deletedAuditRecords = auditRecordMapper.deleteExpiredAuditRecords(auditRecordExpireBefore, limit);
        int deletedClosedIncidents = incidentRecordMapper.deleteExpiredClosedIncidents(closedIncidentExpireBefore, limit);

        return new SyncOperationalDataCleanupResult(
                checkpointExpireBefore,
                errorSampleExpireBefore,
                auditRecordExpireBefore,
                closedIncidentExpireBefore,
                limit,
                deletedCheckpoints,
                deletedErrorSamples,
                deletedAuditRecords,
                deletedClosedIncidents
        );
    }
}
