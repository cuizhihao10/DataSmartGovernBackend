package com.czh.datasmart.govern.task.controller.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * @Author : Cui
 * @Date: 2026/4/18 22:12
 * @Description DataSmart Govern Backend - TaskProgressRequest.java
 * @Version:1.0.0
 *
 * 更新任务进度请求体。
 * 这类请求未来通常会由执行器、调度器或异步消费者回调，因此需要特别注意字段语义稳定。
 */
@Data
public class TaskProgressRequest {

    /**
     * 当前执行 run ID。
     *
     * <p>同一条任务可能经历多次执行尝试，例如第一次失败后重试、被延迟后重新认领、worker 超时后恢复。
     * runId 用于把本次进度回写限定到“当前这一次执行”，避免旧 worker 在租约过期后继续上报进度污染新 run。
     */
    @NotNull(message = "执行 runId 不能为空")
    private Long runId;

    /**
     * 执行器实例 ID。
     *
     * <p>该字段必须与任务当前持有租约的 executorId 匹配，防止其他 worker 或脚本伪造 taskId 回写进度。
     */
    @NotBlank(message = "执行器 ID 不能为空")
    private String executorId;

    /**
     * 幂等键。
     *
     * <p>执行器在网络重试时应复用同一个幂等键。当前阶段会把它写入执行日志 details，
     * 并通过日志查询做轻量重复识别；后续可升级为独立幂等表或 Redis 去重。
     */
    @NotBlank(message = "幂等键不能为空")
    private String idempotencyKey;

    /**
     * 任务进度百分比。
     * 当前用 0-100 的整数表达，最直观也最容易让前端和运维界面展示。
     */
    @NotNull(message = "任务进度不能为空")
    @Min(value = 0, message = "任务进度不能小于 0")
    @Max(value = 100, message = "任务进度不能大于 100")
    private Integer progress;

    /**
     * 断点信息。
     * 用于记录当前执行位置、阶段编号、处理批次或可恢复标识，
     * 为后续断点续跑和问题复盘提供上下文。
     */
    private String checkpoint;
}
