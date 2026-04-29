package com.czh.datasmart.govern.task.controller.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * @Author : Cui
 * @Date: 2026/04/27 01:10
 * @Description DataSmart Govern Backend - TaskExecutionHeartbeatRequest.java
 * @Version:1.0.0
 *
 * 执行器心跳请求。
 *
 * <p>心跳的作用不只是“告诉系统我还活着”，还包括：
 * 1. 续租，避免任务被超时恢复流程误判；
 * 2. 上报进度，供任务列表和监控页面展示；
 * 3. 上报 checkpoint，为失败恢复、断点续跑和重试提供依据。
 */
@Data
public class TaskExecutionHeartbeatRequest {

    /**
     * 执行器实例 ID。
     */
    @NotBlank(message = "执行器 ID 不能为空")
    private String executorId;

    /**
     * 当前进度，0-100。
     */
    @Min(value = 0, message = "进度不能小于 0")
    @Max(value = 100, message = "进度不能大于 100")
    private Integer progress;

    /**
     * 检查点。
     */
    private String checkpoint;

    /**
     * 本次续租秒数。
     */
    @Min(value = 10, message = "续租秒数不能小于 10")
    @Max(value = 3600, message = "续租秒数不能大于 3600")
    private Long leaseSeconds;
}
