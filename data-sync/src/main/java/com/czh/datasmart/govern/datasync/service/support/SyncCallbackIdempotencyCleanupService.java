/**
 * @Author : Cui
 * @Date: 2026/05/08 23:25
 * @Description DataSmart Govern Backend - SyncCallbackIdempotencyCleanupService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.mapper.SyncCallbackIdempotencyMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * data-sync 幂等记录清理服务。
 *
 * <p>这个类故意放在 service/support 包下，而不是写进调度器：
 * 1. 调度器只负责“什么时候触发”，不应该理解表结构和删除 SQL；
 * 2. Mapper 只负责“怎么访问数据库”，不应该承担保留期计算这种业务策略；
 * 3. support 服务承接“按保留期计算过期边界，并执行小批量清理”的业务含义。
 *
 * <p>商业化产品中，幂等表属于可靠性支撑表。它不能随便清空，因为近期重复请求可能还要依赖它；
 * 也不能无限保留，因为 heartbeat/checkpoint 类请求频率很高，长期积累会拖慢唯一索引和备份恢复。
 * 因此这里采用“保留期 + 批次上限”的温和清理策略。
 */
@Component
@RequiredArgsConstructor
public class SyncCallbackIdempotencyCleanupService {

    private final SyncCallbackIdempotencyMapper idempotencyMapper;

    /**
     * 清理超过保留期的幂等记录。
     *
     * @param retentionDays 保留天数，调用方应传入配置类裁剪后的安全值
     * @param limit 单轮删除上限，调用方应传入配置类裁剪后的安全值
     * @return 本轮清理结果，包含边界时间和实际删除数
     */
    public SyncIdempotencyCleanupResult cleanupExpiredRecords(int retentionDays, int limit) {
        LocalDateTime expireBefore = LocalDateTime.now().minusDays(retentionDays);
        int deleted = idempotencyMapper.deleteExpiredRecords(expireBefore, limit);
        return new SyncIdempotencyCleanupResult(expireBefore, retentionDays, limit, deleted);
    }
}
