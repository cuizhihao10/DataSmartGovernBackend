package com.czh.datasmart.govern.datasource.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * @Author : Cui
 * @Date: 2026/4/24 22:40
 * @Description DataSmart Govern Backend - SyncAlertDispatchSchedulerProperties.java
 * @Version:1.0.0
 *
 * 治理告警定时补投调度配置。
 * 这一层负责定义“后台调度器以什么身份、什么频率、什么实例标识、什么租约时长”去处理告警 outbox。
 *
 * 重点回答的问题有：
 * 1. 是否启用自动补投；
 * 2. 多久扫描一次；
 * 3. 当前实例在多实例环境里如何标识自己；
 * 4. 认领到一批告警后，租约多久过期；
 * 5. 每轮最多认领多少条，避免单实例一次吃满所有告警。
 */
@Data
@Component
@ConfigurationProperties(prefix = "datasmart.datasource.sync-alert.scheduler")
public class SyncAlertDispatchSchedulerProperties {

    /**
     * 是否启用定时补投。
     */
    private Boolean enabled = false;

    /**
     * 固定延迟秒数。
     */
    private Integer fixedDelaySeconds = 300;

    /**
     * 系统调度身份的 actorId。
     */
    private Long actorId = 0L;

    /**
     * 系统调度身份角色。
     */
    private String actorRole = "PLATFORM_ADMINISTRATOR";

    /**
     * 系统调度身份所属租户。
     * 平台级补投通常为空。
     */
    private Long actorTenantId;

    /**
     * 调度动作说明。
     */
    private String note = "SYSTEM_SCHEDULED_ALERT_DISPATCH";

    /**
     * 当前调度实例标识。
     * 多实例环境下必须尽量保证不同实例值不同，否则 outbox 认领语义会变弱。
     */
    private String instanceId = "datasource-management-local";

    /**
     * 单轮最多认领多少条待补投告警。
     */
    private Integer claimBatchSize = 50;

    /**
     * 告警 outbox 认领租约秒数。
     * 实例崩溃或长时间卡住后，其他实例可以在租约到期后重新接管。
     */
    private Integer dispatchLeaseSeconds = 120;

    /**
     * 把秒级配置转换成 Spring 调度使用的毫秒值。
     */
    public long getFixedDelayMillis() {
        int safeSeconds = fixedDelaySeconds == null ? 300 : Math.max(30, fixedDelaySeconds);
        return safeSeconds * 1000L;
    }

    /**
     * 返回单轮认领上限。
     */
    public int getSafeClaimBatchSize() {
        return claimBatchSize == null ? 50 : Math.max(1, claimBatchSize);
    }

    /**
     * 返回租约秒数。
     */
    public int getSafeDispatchLeaseSeconds() {
        return dispatchLeaseSeconds == null ? 120 : Math.max(30, dispatchLeaseSeconds);
    }
}
