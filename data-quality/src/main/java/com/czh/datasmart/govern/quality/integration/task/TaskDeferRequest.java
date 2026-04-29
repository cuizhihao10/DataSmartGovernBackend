/**
 * @Author : Cui
 * @Date: 2026/04/29 23:16
 * @Description DataSmart Govern Backend - TaskDeferRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.integration.task;

import lombok.Data;

/**
 * data-quality 调用 task-management 延迟回队列接口的请求模型。
 *
 * <p>该类是 data-quality 自己维护的“局部合同模型”，不要直接复用 task-management 模块里的 DTO。
 * 原因是微服务之间应该通过 HTTP/JSON 合同解耦，而不是通过 Java 包编译期耦合。
 *
 * <p>典型调用场景：
 * data-quality 执行器已经从 task-management claim 到 `DATA_QUALITY_SCAN` 任务，
 * 但在真正执行 SQL 扫描前，本实例并发护栏发现全局、租户或数据源维度已经达到上限。
 * 此时继续扫描会放大资源压力，因此调用 `/tasks/{taskId}/defer`，让任务稍后重新认领。
 */
@Data
public class TaskDeferRequest {

    /**
     * 延迟原因。
     *
     * <p>建议包含触发退避的维度和简短说明，例如：
     * `data-quality 执行器并发护栏拒绝: scope=DATASOURCE, 当前数据源并发已达上限`。
     * task-management 会把该原因写入任务执行日志和本次 run，方便容量分析。
     */
    private String reason;

    /**
     * 延迟秒数。
     *
     * <p>task-management 会把任务 queued_time 推迟该秒数。
     * 到期前任务不会被 claim 查询再次选中，从而避免资源不足时被同一个或其他执行器立刻反复认领。
     */
    private Integer delaySeconds;
}
