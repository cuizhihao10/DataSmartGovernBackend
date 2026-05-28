/**
 * @Author : Cui
 * @Date: 2026/05/08 23:24
 * @Description DataSmart Govern Backend - SyncIdempotencyCleanupResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import java.time.LocalDateTime;

/**
 * data-sync 幂等记录清理结果。
 *
 * <p>这个结果对象用于在“清理服务、调度器、指标组件、日志”之间传递同一份业务事实。
 * 使用 record 可以让返回值保持不可变，避免后续某个环节修改统计值导致日志和指标不一致。
 *
 * @param expireBefore 本轮清理使用的过期边界，lastSeenTime 早于该时间的记录才允许删除
 * @param retentionDays 配置化保留天数，用于审计和排查“为什么这些记录被清掉”
 * @param requestedLimit 本轮请求删除上限，已经由配置类做过安全裁剪
 * @param deleted 本轮实际删除记录数
 */
public record SyncIdempotencyCleanupResult(
        LocalDateTime expireBefore,
        int retentionDays,
        int requestedLimit,
        int deleted
) {
}
