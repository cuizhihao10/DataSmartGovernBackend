/**
 * @Author : Cui
 * @Date: 2026/07/07 23:03
 * @Description DataSmart Govern Backend - SyncTaskScheduleDispatchRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import lombok.Data;

/**
 * 定时同步任务调度触发请求。
 *
 * <p>该 DTO 用于 internal/manual 触发一轮 task scheduler。它和 worker loop 的 run-once 请求不同：</p>
 * <p>1. task scheduler 只负责把到期的 SCHEDULED 任务转换为 QUEUED execution；</p>
 * <p>2. worker loop 才负责认领 QUEUED execution 并调用 datasource-management 执行真实读写；</p>
 * <p>3. 拆成两个入口可以让运维分别排查“为什么没有生成 execution”和“为什么 execution 没有被 worker 执行”。</p>
 */
@Data
public class SyncTaskScheduleDispatchRequest {

    /**
     * 可选租户过滤。
     *
     * <p>为空表示扫描所有租户；租户专属部署或问题排查时可以只扫描指定租户，避免影响其它租户定时任务。</p>
     */
    private Long tenantId;

    /**
     * 单轮最多扫描多少个到期任务。
     *
     * <p>该值会被服务端配置裁剪，避免调用方误传一个很大的数导致调度器长时间占用数据库连接。</p>
     */
    private Integer limit;

    /**
     * 是否只做计划预览。
     *
     * <p>dryRun=true 时不会推进 nextFireTime，也不会创建 execution，只返回扫描到的低敏结果摘要。
     * 它主要用于本地排障和运营台“看看现在会触发哪些任务”。</p>
     */
    private Boolean dryRun;
}
