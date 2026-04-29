package com.czh.datasmart.govern.datasource.schedule;

import com.czh.datasmart.govern.datasource.config.SyncPermissionNotificationProperties;
import com.czh.datasmart.govern.datasource.controller.dto.SyncActionRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncPermissionReminderScanResult;
import com.czh.datasmart.govern.datasource.service.SyncPermissionNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * @Author : Cui
 * @Date: 2026/04/25 00:00
 * @Description DataSmart Govern Backend - SyncPermissionReminderScheduler.java
 * @Version:1.0.0
 *
 * 权限审批超时提醒调度器。
 *
 * 真实企业后台里，审批 SLA 不是“用户自己记得去点列表”就能满足的。
 * 一旦权限变更长期未审批，可能会阻塞项目上线、数据接入、权限回收或审计整改。
 * 这个调度器负责周期性触发提醒扫描，把超时待审批申请单转换成通知对象。
 *
 * 设计上它只负责编排调度，不直接写数据库：
 * 1. 调度频率、系统身份和开关都来自配置；
 * 2. 业务判断、权限校验、去重和通知创建都委托给 SyncPermissionNotificationService；
 * 3. 这样后续无论是人工 API、定时任务、还是消息驱动触发，都能复用同一套业务规则。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SyncPermissionReminderScheduler {

    private final SyncPermissionNotificationService syncPermissionNotificationService;
    private final SyncPermissionNotificationProperties notificationProperties;

    /**
     * 固定延迟扫描审批提醒。
     * 使用 fixedDelay 的原因是：如果上一轮因为数据库慢查询或大量候选单处理耗时较长，下一轮会等上一轮结束后再计时，
     * 从而避免同一个服务实例内出现提醒扫描重叠执行。
     */
    @Scheduled(fixedDelayString = "#{@syncPermissionNotificationProperties.getReminderSchedulerFixedDelayMillis()}")
    public void scanApprovalReminders() {
        if (!Boolean.TRUE.equals(notificationProperties.getReminderSchedulerEnabled())) {
            return;
        }

        SyncActionRequest request = new SyncActionRequest();
        request.setActorId(notificationProperties.getReminderSchedulerActorId());
        request.setActorRole(notificationProperties.getReminderSchedulerActorRole());
        request.setActorTenantId(notificationProperties.getReminderSchedulerActorTenantId());
        request.setNote(notificationProperties.getReminderSchedulerNote());

        try {
            SyncPermissionReminderScanResult result = syncPermissionNotificationService.scanApprovalReminders(request);
            log.info("权限审批提醒调度完成: candidateCount={}, reminderCreatedCount={}, escalationCreatedCount={}, skippedDuplicateCount={}",
                    result.getCandidateCount(),
                    result.getReminderCreatedCount(),
                    result.getEscalationCreatedCount(),
                    result.getSkippedDuplicateCount());
        } catch (Exception exception) {
            log.error("权限审批提醒调度失败: actorRole={}, actorTenantId={}, note={}",
                    request.getActorRole(),
                    request.getActorTenantId(),
                    request.getNote(),
                    exception);
        }
    }
}
