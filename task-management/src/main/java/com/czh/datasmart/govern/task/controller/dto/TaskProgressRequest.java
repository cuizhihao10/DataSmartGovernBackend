package com.czh.datasmart.govern.task.controller.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
