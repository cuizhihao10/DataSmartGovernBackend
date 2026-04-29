/**
 * @Author : Cui
 * @Date: 2026/04/27 22:23
 * @Description DataSmart Govern Backend - QualityExecutionFailRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 质量检测执行器失败回调请求。
 *
 * <p>失败回调用于记录“检测动作没有成功完成”的情况，例如数据源连接失败、
 * SQL 安全校验失败、扫描超时、执行器进程异常、任务被取消等。
 *
 * <p>失败执行通常不会生成质量报告，因为报告代表一次完成的质量判定。
 * 如果扫描动作本身失败，系统应保留 execution 失败原因，等待 task-management 决定是否重试、
 * 是否需要人工介入，避免把技术失败误记成“数据质量不通过”。
 */
@Data
public class QualityExecutionFailRequest {

    /**
     * 失败类型。
     *
     * <p>建议使用稳定编码，例如 DATASOURCE_CONNECT_FAILED、SCAN_TIMEOUT、SQL_REJECTED、
     * EXECUTOR_CRASHED、TASK_CANCELLED。当前不强制枚举，是为了先兼容不同执行器和连接器。
     */
    @Size(max = 128, message = "失败类型长度不能超过 128 个字符")
    private String errorType;

    /**
     * 失败原因。
     *
     * <p>这是运营排障和审计复盘最关键的信息，不能为空。
     */
    @NotBlank(message = "失败原因不能为空")
    @Size(max = 1000, message = "失败原因长度不能超过 1000 个字符")
    private String errorMessage;

    /**
     * 是否建议重试。
     *
     * <p>data-quality 当前只记录该建议，不直接重试任务。
     * 真正是否重试仍应由 task-management 根据任务重试策略、错误类型、重试次数和人工干预状态决定。
     */
    private Boolean retryable;
}
