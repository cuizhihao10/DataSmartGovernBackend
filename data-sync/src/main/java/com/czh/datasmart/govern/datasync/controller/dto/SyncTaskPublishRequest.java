/**
 * @Author : Cui
 * @Date: 2026/07/07 19:09
 * @Description DataSmart Govern Backend - SyncTaskPublishRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 同步任务发布请求。
 *
 * <p>发布是“把任务定义从编辑态推进到可运行生命周期”的动作。它会重新执行模板预检查，
 * 并根据调度配置决定任务进入哪个主状态：</p>
 * <p>1. 定期全量或定期批量且启用调度：进入 SCHEDULED，等待 nextFireTime；</p>
 * <p>2. 普通全量、SQL 自定义、实时或暂不启用调度：进入 CONFIGURED，等待执行入口触发。</p>
 *
 * <p>发布不创建 execution，不搬运数据。它只是把任务定义推到“可以被后续 run/manual-dispatch/scheduler 使用”的安全状态。</p>
 */
@Data
public class SyncTaskPublishRequest {

    /**
     * 是否启用周期调度。
     *
     * <p>为空时采用产品化默认：如果任务已经有 scheduleConfig，则发布后启用调度并进入 SCHEDULED；
     * 如果没有 scheduleConfig，则进入 CONFIGURED。传 false 可以显式表示“保留调度配置但暂不启用”，
     * 适合审批、容量评估或上线窗口尚未就绪的场景。</p>
     */
    private Boolean enableSchedule;

    /**
     * 发布原因。
     *
     * <p>原因会进入审计摘要，用于回答“为什么这个任务从编辑态重新进入可运行生命周期”。</p>
     */
    @Size(max = 500, message = "同步任务发布原因不能超过 500 个字符")
    private String reason;
}
