package com.czh.datasmart.govern.datasource.controller.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @Author : Cui
 * @Date: 2026/04/25 00:00
 * @Description DataSmart Govern Backend - SyncAlertOutboxLeaseRecoveryResult.java
 * @Version:1.0.0
 *
 * 治理告警 outbox 过期租约恢复结果。
 *
 * 分布式 outbox 使用“租约”避免多个服务实例同时投递同一条告警。
 * 但如果某个实例在投递过程中宕机，数据库里可能会留下已经过期的 dispatch_lease_owner。
 * 这类记录不能永远卡住，所以需要一个显式恢复入口把过期租约释放出来，让后续调度器重新认领。
 */
@Data
public class SyncAlertOutboxLeaseRecoveryResult {

    /**
     * 查询的租户范围。
     * 为空表示平台级恢复；非空表示只恢复某个租户范围内的 outbox 记录。
     */
    private Long tenantId;

    /**
     * 本轮扫描到的过期租约数量。
     */
    private Integer candidateCount;

    /**
     * 本轮实际成功释放租约的记录数量。
     * 可能小于 candidateCount，因为并发情况下其他实例可能已经先一步恢复或重新认领。
     */
    private Integer recoveredCount;

    /**
     * 本轮成功释放租约的告警 ID。
     */
    private List<Long> recoveredAlertIds;

    /**
     * 恢复动作发生时间。
     */
    private LocalDateTime recoveredAt;
}
