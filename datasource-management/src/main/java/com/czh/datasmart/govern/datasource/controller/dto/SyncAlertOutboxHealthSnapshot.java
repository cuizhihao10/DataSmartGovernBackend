package com.czh.datasmart.govern.datasource.controller.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * @Author : Cui
 * @Date: 2026/04/25 00:00
 * @Description DataSmart Govern Backend - SyncAlertOutboxHealthSnapshot.java
 * @Version:1.0.0
 *
 * 治理告警 outbox 健康快照。
 *
 * 当前 datasource-management 已经把告警投递从“直接调用外部通道”升级成了“先落库、再认领、再投递”的 outbox 模式。
 * outbox 模式的优势是可靠，但它也引入了新的运营问题：队列是否积压、失败是否过多、租约是否过期、死信是否无人处理。
 * 这个 DTO 就是为这些运营问题准备的只读快照，既可以给前端告警中心展示，也可以在未来被 observability 模块采集为指标。
 */
@Data
public class SyncAlertOutboxHealthSnapshot {

    /**
     * 查询的租户范围。
     * 为空表示平台管理员正在看平台级聚合视图；非空表示只看某个租户的 outbox 状态。
     */
    private Long tenantId;

    /**
     * 等待投递的告警数量。
     */
    private Long pendingCount;

    /**
     * 最近投递失败、仍可重试的告警数量。
     */
    private Long failedCount;

    /**
     * 已进入死信状态、需要人工重新入队或修复通道配置的告警数量。
     */
    private Long deadLetterCount;

    /**
     * 当前被某个调度实例持有租约的 outbox 记录数量。
     */
    private Long leasedCount;

    /**
     * 租约已经过期但仍残留 owner 的记录数量。
     * 这个值如果长期大于 0，通常意味着调度实例宕机、异常退出或 release 逻辑没有被执行。
     */
    private Long expiredLeaseCount;

    /**
     * 当前已经到达 next_delivery_attempt_at、理论上可以被补投的数量。
     */
    private Long dueRetryableCount;

    /**
     * 最早一条到期待投递记录的时间。
     * 运维上可以通过它判断 outbox 积压年龄，而不只是看数量。
     */
    private LocalDateTime oldestDueRetryAt;

    /**
     * 快照生成时间。
     */
    private LocalDateTime generatedAt;
}
