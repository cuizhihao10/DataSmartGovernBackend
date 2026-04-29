package com.czh.datasmart.govern.datasource.support;

/**
 * @Author : Cui
 * @Date: 2026/4/20 09:18
 * @Description DataSmart Govern Backend - SyncAlertType.java
 * @Version:1.0.0
 *
 * 同步治理告警类型。
 * 当前先围绕已经落地的队列治理能力建第一版告警分类，后续可以继续扩展到：
 * - 执行失败风暴；
 * - 连接器故障；
 * - 审批积压；
 * - 并发策略冲突；
 * - 配额耗尽等场景。
 */
public enum SyncAlertType {
    QUEUE_PRESSURE,
    QUEUE_AGING
}
