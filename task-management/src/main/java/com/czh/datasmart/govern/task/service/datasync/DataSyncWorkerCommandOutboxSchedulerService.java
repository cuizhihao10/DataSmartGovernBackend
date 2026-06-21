/**
 * @Author : Cui
 * @Date: 2026/06/21 00:35
 * @Description DataSmart Govern Backend - DataSyncWorkerCommandOutboxSchedulerService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.datasync;

import com.czh.datasmart.govern.task.config.AgentAsyncToolWorkerProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * DataSync worker outbox 后台调度编排服务。
 *
 * <p>该服务负责把 6.00、6.01、6.02 已完成的几个能力串成一轮可自动执行的后台闭环：</p>
 * <p>1. 先调用 {@link DataSyncWorkerCommandOutboxRecoveryService#recoverStaleDispatching(DataSyncWorkerOutboxRecoveryRequest)}
 * 释放长时间停留在 DISPATCHING 的悬挂命令；</p>
 * <p>2. 再调用 {@link DataSyncWorkerCommandDeliveryService#dispatchBatch(DataSyncWorkerOutboxDispatchBatchRequest)}
 * 领取并投递 PENDING/到期 DEFERRED 命令；</p>
 * <p>3. 返回一份低敏 tick 结果，供 scheduler 打日志、测试断言和未来指标采集使用。</p>
 *
 * <p>为什么恢复必须在投递前执行：</p>
 * <p>如果先投递再恢复，队列中已悬挂的 DISPATCHING 命令仍然不会被普通 claim 扫描到，
 * 本轮调度只能处理新命令，老命令继续沉没。先恢复可以把 stale 命令释放回 DEFERRED 或 DEAD_LETTER，
 * 让系统逐步回到可控状态。</p>
 *
 * <p>该服务本身不做开关判断。是否允许运行由 {@link DataSyncWorkerCommandOutboxScheduler} 负责，
 * 这样可以把“定时触发安全门”和“业务编排顺序”拆开，测试也更清晰。</p>
 */
@Service
@RequiredArgsConstructor
public class DataSyncWorkerCommandOutboxSchedulerService {

    private static final String SCHEDULER_TICK_SCHEMA_VERSION =
            "datasmart.task.data-sync-worker-outbox.scheduler-tick.v1";

    private final AgentAsyncToolWorkerProperties properties;
    private final DataSyncWorkerCommandOutboxRecoveryService recoveryService;
    private final DataSyncWorkerCommandDeliveryService deliveryService;

    /**
     * 执行一轮 DataSync outbox 后台调度。
     *
     * <p>本方法会产生数据库写入和下游 HTTP 调用副作用，因此只能由受控 scheduler、内部运维入口或测试显式调用。
     * 当前阶段不按租户分片，先使用全局小批量策略；后续高并发生产化时，应扩展为租户/项目分片、公平队列、优先级队列或 Redis/DB 级全局配额。</p>
     *
     * @return 本轮调度的低敏聚合结果。
     */
    public DataSyncWorkerOutboxSchedulerTickResult dispatchScheduledTick() {
        LocalDateTime tickTime = LocalDateTime.now();
        String executorId = normalizedExecutorId();
        DataSyncWorkerOutboxRecoveryResult recoveryResult = recoveryService.recoverStaleDispatching(
                new DataSyncWorkerOutboxRecoveryRequest(
                        executorId,
                        null,
                        null,
                        properties.getDataSyncOutboxRecoveryLimitPerTick()
                )
        );
        DataSyncWorkerOutboxDispatchBatchResult dispatchResult = deliveryService.dispatchBatch(
                new DataSyncWorkerOutboxDispatchBatchRequest(
                        executorId,
                        null,
                        null,
                        properties.getDataSyncOutboxDispatchLimitPerTick(),
                        true
                )
        );
        return new DataSyncWorkerOutboxSchedulerTickResult(
                SCHEDULER_TICK_SCHEMA_VERSION,
                executorId,
                tickTime,
                recoveryResult,
                dispatchResult,
                aggregateWarnings(recoveryResult, dispatchResult)
        );
    }

    private String normalizedExecutorId() {
        String executorId = properties.getExecutorId();
        if (executorId == null || executorId.isBlank()) {
            return "task-management-data-sync-outbox-scheduler";
        }
        return executorId.trim();
    }

    private List<String> aggregateWarnings(DataSyncWorkerOutboxRecoveryResult recoveryResult,
                                           DataSyncWorkerOutboxDispatchBatchResult dispatchResult) {
        List<String> warnings = new ArrayList<>();
        if (recoveryResult != null) {
            warnings.addAll(recoveryResult.warnings());
        }
        if (dispatchResult != null) {
            warnings.addAll(dispatchResult.warnings());
        }
        return List.copyOf(warnings);
    }
}
