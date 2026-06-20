/**
 * @Author : Cui
 * @Date: 2026/06/20 23:35
 * @Description DataSmart Govern Backend - DataSyncWorkerCommandOutboxRecoveryService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.datasync;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.czh.datasmart.govern.task.config.AgentAsyncToolWorkerProperties;
import com.czh.datasmart.govern.task.entity.DataSyncWorkerCommandOutbox;
import com.czh.datasmart.govern.task.mapper.DataSyncWorkerCommandOutboxMapper;
import com.czh.datasmart.govern.task.support.DataSyncWorkerCommandOutboxStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * DataSync worker outbox 超时恢复服务。
 *
 * <p>该服务补齐 outbox 投递闭环里非常关键的一段可靠性能力：命令已经被 dispatcher 领取并进入
 * {@code DISPATCHING}，但 worker 在调用 datasource-management 前后发生崩溃、进程重启、网络中断或超时，
 * 导致本地 outbox 没有收到最终 receipt。没有恢复逻辑时，这类命令会永久停留在 DISPATCHING，
 * 既不会被普通 dispatcher 再次领取，也不会进入 DEAD_LETTER，运维只能手工改库。</p>
 *
 * <p>恢复策略：</p>
 * <p>1. 只扫描 status=DISPATCHING 且 dispatchedAt 早于配置阈值的记录；</p>
 * <p>2. 如果 attemptCount 尚未达到最大尝试次数，把命令恢复为 DEFERRED，并设置 nextRetryAt；</p>
 * <p>3. 如果 attemptCount 已达到最大尝试次数，把命令推进到 DEAD_LETTER，停止自动重试；</p>
 * <p>4. 每一条记录都使用条件更新：id 相同、状态仍是 DISPATCHING、dispatchedAt 仍然早于 cutoff 时才更新。
 * 这样可以避免恢复器覆盖正在成功回写的投递线程。</p>
 *
 * <p>副作用边界：</p>
 * <p>本服务只修改 task-management 本地 outbox 账本，不直接调用 datasource-management，也不读取或返回 payloadJson。
 * 恢复动作本质上是“释放悬挂租约并重新进入调度状态”，而不是执行真实数据同步。</p>
 */
@Service
@RequiredArgsConstructor
public class DataSyncWorkerCommandOutboxRecoveryService {

    private static final String RECOVERY_SCHEMA_VERSION =
            "datasmart.task.data-sync-worker-outbox.stale-recovery.v1";
    private static final String RECOVERY_POLICY =
            "仅恢复超过配置阈值的 DISPATCHING；未达最大尝试次数转 DEFERRED，达到上限转 DEAD_LETTER；响应保持低敏";
    private static final int DEFAULT_RECOVERY_LIMIT = 20;
    private static final int MAX_RECOVERY_LIMIT = 100;
    private static final int MIN_TIMEOUT_SECONDS = 1;
    private static final int MIN_RETRY_AFTER_SECONDS = 1;

    private final DataSyncWorkerCommandOutboxMapper outboxMapper;
    private final AgentAsyncToolWorkerProperties properties;

    /**
     * 恢复长时间停留在 DISPATCHING 的 DataSync worker outbox 命令。
     *
     * <p>典型触发来源：</p>
     * <p>1. 后台 scheduler 定期扫描 stale DISPATCHING；</p>
     * <p>2. 运维控制面在发现 diagnostics 中 DISPATCHING 长时间不下降时手动触发；</p>
     * <p>3. 未来 worker heartbeat 失联后，由补偿器按 executor/lease 维度触发。</p>
     *
     * <p>该方法有数据库写副作用，因此必须运行在事务中。为了避免长事务和大批量锁竞争，
     * 单轮扫描数量会被限制在 {@link #MAX_RECOVERY_LIMIT} 以内。</p>
     *
     * @param request 恢复请求，包含 executorId、租户/项目过滤和本轮 limit。
     * @return 本轮恢复的低敏统计与已恢复记录视图。
     */
    @Transactional
    public DataSyncWorkerOutboxRecoveryResult recoverStaleDispatching(DataSyncWorkerOutboxRecoveryRequest request) {
        validateRequest(request);
        LocalDateTime now = LocalDateTime.now();
        int effectiveLimit = clamp(request.getLimit(), DEFAULT_RECOVERY_LIMIT, MAX_RECOVERY_LIMIT);
        int timeoutSeconds = Math.max(MIN_TIMEOUT_SECONDS, properties.getDataSyncOutboxDispatchingTimeoutSeconds());
        int retryAfterSeconds = Math.max(
                MIN_RETRY_AFTER_SECONDS,
                properties.getDataSyncOutboxStaleRecoveryRetryAfterSeconds()
        );
        int maxAttempts = Math.max(1, properties.getDataSyncOutboxMaxAttempts());
        LocalDateTime staleBefore = now.minusSeconds(timeoutSeconds);
        List<DataSyncWorkerCommandOutbox> candidates = outboxMapper.selectList(
                buildStaleDispatchingWrapper(request, staleBefore, effectiveLimit)
        );

        List<DataSyncWorkerCommandOutboxView> recoveredRecords = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int deferredCount = 0;
        int deadLetterCount = 0;
        int skippedCount = 0;

        for (DataSyncWorkerCommandOutbox candidate : candidates) {
            RecoveryDecision decision = decideRecovery(candidate, maxAttempts, retryAfterSeconds, now);
            int updated = tryRecoverOne(candidate, staleBefore, decision);
            if (updated <= 0) {
                skippedCount++;
                warnings.add("跳过 outbox " + candidate.getOutboxId()
                        + "：状态或 dispatchedAt 已被其他线程更新，避免覆盖并发投递结果");
                continue;
            }

            applyDecisionToMemory(candidate, decision);
            recoveredRecords.add(DataSyncWorkerCommandOutboxViewAssembler.toView(candidate));
            if (decision.status() == DataSyncWorkerCommandOutboxStatus.DEAD_LETTER) {
                deadLetterCount++;
            } else {
                deferredCount++;
            }
        }

        buildWarnings(warnings, candidates.size(), deferredCount, deadLetterCount);
        return new DataSyncWorkerOutboxRecoveryResult(
                RECOVERY_SCHEMA_VERSION,
                request.getExecutorId().trim(),
                now,
                timeoutSeconds,
                retryAfterSeconds,
                staleBefore,
                request.getLimit(),
                effectiveLimit,
                candidates.size(),
                recoveredRecords.size(),
                deferredCount,
                deadLetterCount,
                skippedCount,
                RECOVERY_POLICY,
                List.copyOf(recoveredRecords),
                List.copyOf(warnings)
        );
    }

    private LambdaQueryWrapper<DataSyncWorkerCommandOutbox> buildStaleDispatchingWrapper(
            DataSyncWorkerOutboxRecoveryRequest request,
            LocalDateTime staleBefore,
            int limit) {
        LambdaQueryWrapper<DataSyncWorkerCommandOutbox> wrapper = new LambdaQueryWrapper<>();
        if (request.getTenantId() != null) {
            wrapper.eq(DataSyncWorkerCommandOutbox::getTenantId, request.getTenantId());
        }
        if (request.getProjectId() != null) {
            wrapper.eq(DataSyncWorkerCommandOutbox::getProjectId, request.getProjectId());
        }
        wrapper.eq(DataSyncWorkerCommandOutbox::getStatus, DataSyncWorkerCommandOutboxStatus.DISPATCHING.name())
                .and(row -> row.isNull(DataSyncWorkerCommandOutbox::getDispatchedAt)
                        .or()
                        .le(DataSyncWorkerCommandOutbox::getDispatchedAt, staleBefore))
                .orderByAsc(DataSyncWorkerCommandOutbox::getDispatchedAt)
                .orderByAsc(DataSyncWorkerCommandOutbox::getId)
                .last("LIMIT " + limit);
        return wrapper;
    }

    private RecoveryDecision decideRecovery(DataSyncWorkerCommandOutbox candidate,
                                            int maxAttempts,
                                            int retryAfterSeconds,
                                            LocalDateTime now) {
        int attempts = safeInt(candidate.getAttemptCount());
        if (attempts >= maxAttempts) {
            return new RecoveryDecision(
                    DataSyncWorkerCommandOutboxStatus.DEAD_LETTER,
                    null,
                    "DataSync worker command 在 DISPATCHING 超时后已达到最大尝试次数 "
                            + attempts + "/" + maxAttempts + "，进入 DEAD_LETTER 等待人工处理",
                    now
            );
        }
        return new RecoveryDecision(
                DataSyncWorkerCommandOutboxStatus.DEFERRED,
                now.plusSeconds(retryAfterSeconds),
                "DataSync worker command 在 DISPATCHING 超时后恢复为 DEFERRED，等待下一轮 dispatcher 重新领取",
                now
        );
    }

    private int tryRecoverOne(DataSyncWorkerCommandOutbox candidate,
                              LocalDateTime staleBefore,
                              RecoveryDecision decision) {
        DataSyncWorkerCommandOutbox update = new DataSyncWorkerCommandOutbox();
        update.setStatus(decision.status().name());
        update.setSideEffectStarted(Boolean.TRUE.equals(candidate.getSideEffectStarted()));
        update.setSideEffectExecuted(false);
        update.setLastError(decision.message());
        update.setNextRetryAt(decision.nextRetryAt());
        update.setUpdateTime(decision.updateTime());
        return outboxMapper.update(
                update,
                new LambdaUpdateWrapper<DataSyncWorkerCommandOutbox>()
                        .eq(DataSyncWorkerCommandOutbox::getId, candidate.getId())
                        .eq(DataSyncWorkerCommandOutbox::getStatus, DataSyncWorkerCommandOutboxStatus.DISPATCHING.name())
                        .and(row -> row.isNull(DataSyncWorkerCommandOutbox::getDispatchedAt)
                                .or()
                                .le(DataSyncWorkerCommandOutbox::getDispatchedAt, staleBefore))
        );
    }

    private void applyDecisionToMemory(DataSyncWorkerCommandOutbox candidate, RecoveryDecision decision) {
        candidate.setStatus(decision.status().name());
        candidate.setSideEffectStarted(Boolean.TRUE.equals(candidate.getSideEffectStarted()));
        candidate.setSideEffectExecuted(false);
        candidate.setLastError(decision.message());
        candidate.setNextRetryAt(decision.nextRetryAt());
        candidate.setUpdateTime(decision.updateTime());
    }

    private void buildWarnings(List<String> warnings,
                               int scannedCount,
                               int deferredCount,
                               int deadLetterCount) {
        if (scannedCount == 0) {
            warnings.add("当前没有超过超时阈值的 DISPATCHING outbox 命令");
        }
        if (deferredCount > 0) {
            warnings.add("部分 DISPATCHING outbox 已恢复为 DEFERRED，后续 dispatcher 会在 nextRetryAt 到达后重新领取");
        }
        if (deadLetterCount > 0) {
            warnings.add("部分 DISPATCHING outbox 已达到最大尝试次数并进入 DEAD_LETTER，需要运维或平台管理员人工确认");
        }
    }

    private void validateRequest(DataSyncWorkerOutboxRecoveryRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("DataSync worker outbox 恢复请求不能为空");
        }
        if (request.getExecutorId() == null || request.getExecutorId().isBlank()) {
            throw new IllegalArgumentException("executorId 不能为空");
        }
    }

    private int clamp(Integer requested, int defaultValue, int maxValue) {
        if (requested == null || requested <= 0) {
            return defaultValue;
        }
        return Math.min(requested, maxValue);
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    /**
     * 单条 stale outbox 的恢复决策。
     *
     * <p>使用 record 而不是多个散落变量，是为了让“目标状态、下次重试时间、低敏错误摘要、更新时间”
     * 作为一个整体传递，避免后续维护时出现状态和时间不匹配。</p>
     */
    private record RecoveryDecision(
            DataSyncWorkerCommandOutboxStatus status,
            LocalDateTime nextRetryAt,
            String message,
            LocalDateTime updateTime
    ) {
    }
}
