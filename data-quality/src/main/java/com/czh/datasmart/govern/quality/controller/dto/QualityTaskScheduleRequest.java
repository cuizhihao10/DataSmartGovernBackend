/**
 * @Author : Cui
 * @Date: 2026/04/27 22:20
 * @Description DataSmart Govern Backend - QualityTaskScheduleRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.controller.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 提交质量检测任务请求。
 *
 * <p>这个请求用于把“规则 + 扫描计划”提交到 task-management。
 * 它不是直接执行质量检测，而是创建一条等待执行器认领的后台任务。
 *
 * <p>为什么要再包一层 schedule request？
 * 因为任务提交除了扫描参数，还会有任务中心关心的调度属性：
 * 优先级、重试次数、操作者、业务原因、是否只预览等。
 */
@Data
public class QualityTaskScheduleRequest {

    /**
     * 扫描计划参数。
     *
     * <p>如果为空，服务层会按默认 SAMPLE_SCAN 生成计划。
     */
    @Valid
    private QualityScanPlanRequest scanPlan;

    /**
     * 租户 ID。
     *
     * <p>当前用于写入质量任务 payload，供执行器做租户级并发护栏。
     * 在真正的商业化部署中，该字段不应该完全由前端自由填写，而应该优先来自 gateway 注入的可信租户上下文。
     * 这里先保留请求字段，是为了让本地联调、测试数据和后续权限中心接入有明确承载位置。
     */
    @Min(value = 0, message = "租户 ID 不能小于 0")
    private Long tenantId;

    /**
     * 任务优先级。
     *
     * <p>允许传 HIGH、MEDIUM、LOW；为空时使用配置中的默认优先级。
     */
    @Size(max = 32, message = "任务优先级长度不能超过 32 个字符")
    private String priority;

    /**
     * 最大重试次数。
     */
    @Min(value = 0, message = "最大重试次数不能小于 0")
    private Integer maxRetryCount;

    /**
     * 提交原因。
     *
     * <p>用于任务描述和审计上下文，例如“每日质量巡检”“人工复查客户表手机号规则”。
     */
    @Size(max = 512, message = "提交原因长度不能超过 512 个字符")
    private String reason;

    /**
     * 是否只生成计划、不提交任务。
     *
     * <p>适合前端预览和人工审核流程。true 时接口返回 scanPlan，但不会调用 task-management。
     */
    private Boolean dryRun;
}
