/**
 * @Author : Cui
 * @Date: 2026/08/11 18:40
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryTriggerOutboxScheduler.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.scheduler;

import com.czh.datasmart.govern.datasync.config.SyncAutopilotRecoveryTriggerProperties;
import com.czh.datasmart.govern.datasync.service.support.SyncAutopilotRecoveryTriggerOutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 周期性补偿未送达的 Autopilot trigger outbox。
 *
 * <p>scheduler 不生成新恢复决策，只重放已经持久化且仍在尝试预算内的事件，因此服务重启、
 * Kafka 短时故障和多实例竞争都不会改变用户最初授权的含义。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SyncAutopilotRecoveryTriggerOutboxScheduler {

    private final SyncAutopilotRecoveryTriggerOutboxService outboxService;
    private final SyncAutopilotRecoveryTriggerProperties properties;

    /** 按 fixedDelay 扫描 due 记录；配置关闭时不访问数据库。 */
    @Scheduled(
            initialDelayString = "${datasmart.data-sync.autopilot-recovery-trigger.initial-delay-ms:30000}",
            fixedDelayString = "${datasmart.data-sync.autopilot-recovery-trigger.fixed-delay-ms:15000}")
    public void dispatchDue() {
        if (!properties.isEnabled() || !properties.isSchedulerEnabled()) {
            return;
        }
        int delivered = outboxService.dispatchDue();
        if (delivered > 0) {
            log.info("Delivered {} durable Autopilot recovery trigger events", delivered);
        }
    }
}
