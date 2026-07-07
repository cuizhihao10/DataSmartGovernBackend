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
 * 并根据风险、审批事实和调度配置决定任务进入哪个主状态：</p>
 * <p>1. 需要审批但尚未确认：进入 PENDING_APPROVAL，不允许执行，也不会被调度器扫描；</p>
 * <p>2. 无需审批或审批已确认，且启用调度：进入 SCHEDULED，等待 nextFireTime；</p>
 * <p>3. 无需审批或审批已确认，但不启用调度：进入 CONFIGURED，等待手工调度。</p>
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
     * 是否确认审批已经完成。
     *
     * <p>高风险任务，例如全库迁移、自定义 SQL、覆盖式写入，发布时必须看到可信角色提交的审批确认事实。
     * 普通用户不能仅靠请求体里的布尔值绕过审批，服务层会校验操作者角色。</p>
     */
    private Boolean approvalConfirmed;

    /**
     * 审批事实低敏引用。
     *
     * <p>该字段只能保存审批系统、工单系统或人工确认单的短引用，例如 approval:sync-20260707-001。
     * 不允许保存审批正文、SQL、表清单、字段映射、样本数据或连接信息。</p>
     */
    @Size(max = 160, message = "审批事实 ID 不能超过 160 个字符")
    private String approvalFactId;

    /**
     * 发布原因。
     *
     * <p>原因会进入审计摘要，用于回答“为什么这个任务从编辑态重新进入可运行生命周期”。</p>
     */
    @Size(max = 500, message = "同步任务发布原因不能超过 500 个字符")
    private String reason;
}
