package com.czh.datasmart.govern.datasource.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * @Author : Cui
 * @Date: 2026/4/19 21:01
 * @Description DataSmart Govern Backend - SyncExecutorProperties.java
 * @Version:1.0.0
 *
 * 同步执行器运行时配置。
 * 这组配置不是给普通业务用户直接操作的，而是给控制面、调度器和未来执行器节点使用的运行边界参数。
 *
 * 当前这组参数主要服务于六类平台治理问题：
 * 1. 认领治理：一次认领最多扫描多少候选任务，避免简单实现直接全表扫描。
 * 2. 租约治理：执行器认领后租约持续多久、认领前是否自动恢复过期租约。
 * 3. 并发治理：每个租户、每个数据源最多允许多少活跃任务同时运行。
 * 4. 队列治理：当待执行任务大量堆积时，平台是否有全局与租户级积压保护。
 * 5. 公平治理：认领时是否尽量照顾不同租户，避免大租户长期淹没小租户。
 * 6. 老化治理：排队过久的任务多长时间后需要被巡检、标记和触发预警。
 *
 * 这些配置先以本地 properties 的方式固化下来，后续如果做成可运营的策略中心，
 * 也可以把这些字段映射成“租户策略”“平台策略”或“执行器池策略”。
 */
@Data
@Component
@ConfigurationProperties(prefix = "datasmart.datasource.sync-executor")
public class SyncExecutorProperties {

    /**
     * 执行器一次认领前最多扫描多少条候选任务。
     * 当前实现还是“数据库粗过滤 + Java 侧排序”的第一版，所以需要显式限制扫描窗口。
     */
    private Integer claimScanLimit = 50;

    /**
     * 默认租约时长，单位秒。
     * 执行器需要在租约期间持续发送心跳，控制面才会认定这次执行仍然有效。
     */
    private Integer leaseDurationSeconds = 120;

    /**
     * 显式恢复过期租约时，一次最多处理多少条任务。
     * 这是为了避免管理员或自动恢复逻辑一次性改写过多运行态记录。
     */
    private Integer expiredLeaseRecoveryBatchSize = 50;

    /**
     * 执行器认领前是否自动先做一轮过期租约恢复。
     */
    private Boolean autoRecoverExpiredLeasesBeforeClaim = true;

    /**
     * 租约过期后是否自动重新放回队列。
     * 如果关闭，系统会更保守地把任务打到失败并等待人工处理。
     */
    private Boolean autoRequeueExpiredLeases = true;

    /**
     * 每个租户最多允许多少个活跃运行任务。
     */
    private Integer maxRunningTasksPerTenant = 4;

    /**
     * 每个数据源最多允许多少个活跃任务同时占用。
     * 这里会同时约束源端和目标端，避免单一数据库或外部系统被打爆。
     */
    private Integer maxRunningTasksPerDatasource = 3;

    /**
     * 全局待执行队列最大允许多少条任务。
     */
    private Integer maxQueuedTasksGlobal = 200;

    /**
     * 每个租户最大允许多少条待执行任务。
     */
    private Integer maxQueuedTasksPerTenant = 50;

    /**
     * 是否开启租户维度的公平认领。
     */
    private Boolean enableTenantQueueFairness = true;

    /**
     * 是否允许人工直接运行任务而不经过队列。
     * 当前保留该能力是为了开发调试与人工排障，真实生产环境通常会更倾向于关闭。
     */
    private Boolean allowManualRunBypassQueue = true;

    /**
     * 判定“队列老化”的阈值秒数。
     * 例如默认 15 分钟仍未被认领，就意味着需要运营关注执行器容量、优先级策略或上游提交行为。
     */
    private Integer queuedTaskAgingThresholdSeconds = 900;

    /**
     * 一次老化巡检最多检查多少条老化候选任务。
     * 这里限制批量大小，是为了避免巡检在高积压时一次更新过多记录。
     */
    private Integer queuedTaskAgingScanLimit = 100;

    /**
     * 全局队列预警阈值。
     * 一旦达到该阈值，就算还没触发“硬上限”，也应该提示运营关注。
     */
    private Integer queueAlertThresholdGlobal = 120;

    /**
     * 单租户队列预警阈值。
     * 它通常用于识别“某个租户提交过猛”或“该租户绑定的执行能力不足”。
     */
    private Integer queueAlertThresholdPerTenant = 20;
}
