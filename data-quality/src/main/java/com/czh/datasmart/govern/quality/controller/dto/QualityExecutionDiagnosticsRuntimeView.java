/**
 * @Author : Cui
 * @Date: 2026/06/22 20:19
 * @Description DataSmart Govern Backend - QualityExecutionDiagnosticsRuntimeView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.controller.dto;

import lombok.Data;

/**
 * data-quality 执行器运行配置快照。
 *
 * <p>质量检测失败不一定是规则本身的问题，也可能是执行器没有启用、调度器关闭、
 * 单轮批量太小、并发护栏过紧、task-management failOpen 配置不符合预期等。
 * 因此诊断接口需要把关键运行配置以低敏方式返回给运营台，帮助快速定位“为什么没有任务在跑”。</p>
 *
 * <p>这里刻意不返回 baseUrl、内部 endpoint、认证头、连接配置或任何密钥。
 * 服务间地址和安全凭据属于部署敏感信息，应该只出现在受控配置中心、日志脱敏摘要或专门的运维系统中。</p>
 */
@Data
public class QualityExecutionDiagnosticsRuntimeView {

    /**
     * data-quality 是否启用 task-management 集成。
     */
    private Boolean taskManagementIntegrationEnabled;

    /**
     * 内置质量执行器 coordinator 是否允许工作。
     */
    private Boolean executorCoordinatorEnabled;

    /**
     * 后台 scheduler 是否自动触发 coordinator。
     */
    private Boolean executorSchedulerEnabled;

    /**
     * 本实例并发护栏是否启用。
     */
    private Boolean executorConcurrencyGuardEnabled;

    /**
     * 执行器实例 ID。
     */
    private String executorId;

    /**
     * task-management 中用于认领的任务类型。
     */
    private String taskType;

    /**
     * 调度器首次触发前等待秒数。
     */
    private Integer schedulerInitialDelaySeconds;

    /**
     * 调度器两轮之间的固定延迟秒数。
     */
    private Integer schedulerFixedDelaySeconds;

    /**
     * 单轮调度最多处理的任务数，已经过安全上限裁剪。
     */
    private Integer schedulerMaxRunsPerTick;

    /**
     * 本实例允许同时运行的质量扫描总数。
     */
    private Integer maxConcurrentRunsGlobal;

    /**
     * 本实例内单租户允许同时运行的质量扫描数。
     */
    private Integer maxConcurrentRunsPerTenant;

    /**
     * 本实例内单数据源允许同时运行的质量扫描数。
     */
    private Integer maxConcurrentRunsPerDatasource;

    /**
     * 并发护栏触发后回退给 task-management 的延迟秒数。
     */
    private Integer throttleDeferSeconds;

    /**
     * 执行器认领任务后的默认租约秒数。
     */
    private Long leaseSeconds;

    /**
     * task-management 调用失败时是否允许 fail-open。
     */
    private Boolean failOpen;

    /**
     * 服务间调用来源标识。
     */
    private String sourceService;
}
