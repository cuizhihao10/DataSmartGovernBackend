package com.czh.datasmart.govern.datasource.schedule;

import com.czh.datasmart.govern.datasource.config.SyncAlertDispatchSchedulerProperties;
import com.czh.datasmart.govern.datasource.controller.dto.SyncActionRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncAlertDispatchBatchResult;
import com.czh.datasmart.govern.datasource.service.SyncGovernanceAlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * @Author : Cui
 * @Date: 2026/4/24 22:40
 * @Description DataSmart Govern Backend - SyncAlertDispatchScheduler.java
 * @Version:1.0.0
 *
 * 治理告警自动补投调度器。
 * 当前它不再只是简单扫描“到期可重试告警”，而是把告警对象当作分布式 outbox 项来处理：
 * 1. 先按实例标识去认领一批到期告警；
 * 2. 认领成功后再执行投递；
 * 3. 投递结束后主动释放租约；
 * 4. 如果实例中途崩溃，其他实例可以在租约过期后重新接管。
 *
 * 这让当前模块在多实例部署时，已经具备第一版“去重补投、避免并发重复发送”的运营基线。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SyncAlertDispatchScheduler {

    private final SyncGovernanceAlertService syncGovernanceAlertService;
    private final SyncAlertDispatchSchedulerProperties syncAlertDispatchSchedulerProperties;

    /**
     * 固定延迟扫描可重试告警。
     * 这里使用 fixedDelay 而不是 fixedRate，是为了尽量避免上一轮尚未结束时又并发开启新一轮调度。
     */
    @Scheduled(fixedDelayString = "#{@syncAlertDispatchSchedulerProperties.getFixedDelayMillis()}")
    public void dispatchRetryableAlerts() {
        if (!Boolean.TRUE.equals(syncAlertDispatchSchedulerProperties.getEnabled())) {
            return;
        }

        SyncActionRequest request = new SyncActionRequest();
        request.setActorId(syncAlertDispatchSchedulerProperties.getActorId());
        request.setActorRole(syncAlertDispatchSchedulerProperties.getActorRole());
        request.setActorTenantId(syncAlertDispatchSchedulerProperties.getActorTenantId());
        request.setNote(syncAlertDispatchSchedulerProperties.getNote());

        try {
            SyncAlertDispatchBatchResult result = syncGovernanceAlertService.claimAndDispatchRetryableAlerts(
                    request,
                    syncAlertDispatchSchedulerProperties.getInstanceId(),
                    syncAlertDispatchSchedulerProperties.getSafeClaimBatchSize(),
                    syncAlertDispatchSchedulerProperties.getSafeDispatchLeaseSeconds());
            log.info("治理告警 outbox 调度完成: instanceId={}, candidateCount={}, claimedCount={}, attemptedCount={}, sentCount={}, failedCount={}, deadLetterCount={}",
                    syncAlertDispatchSchedulerProperties.getInstanceId(),
                    result.getCandidateCount(),
                    result.getClaimedCount(),
                    result.getAttemptedCount(),
                    result.getSentCount(),
                    result.getFailedCount(),
                    result.getDeadLetterCount());
        } catch (Exception exception) {
            log.error("治理告警 outbox 调度失败: instanceId={}, actorRole={}, actorTenantId={}, note={}",
                    syncAlertDispatchSchedulerProperties.getInstanceId(),
                    request.getActorRole(),
                    request.getActorTenantId(),
                    request.getNote(),
                    exception);
        }
    }
}
