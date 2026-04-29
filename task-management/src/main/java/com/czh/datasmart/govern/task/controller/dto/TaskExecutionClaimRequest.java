package com.czh.datasmart.govern.task.controller.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * @Author : Cui
 * @Date: 2026/04/27 01:10
 * @Description DataSmart Govern Backend - TaskExecutionClaimRequest.java
 * @Version:1.0.0
 *
 * 执行器认领任务请求。
 *
 * <p>执行器不是直接把任务 ID 改成 RUNNING，而是向任务中心申请认领下一条可执行任务。
 * 任务中心负责按优先级、类型、状态和租约规则返回任务，这样才能在多执行器场景下避免重复执行。
 */
@Data
public class TaskExecutionClaimRequest {

    /**
     * 执行器实例 ID。
     *
     * <p>必须稳定且可排障，例如 worker-01、pod 名、agent-runtime 实例 ID。
     */
    @NotBlank(message = "执行器 ID 不能为空")
    private String executorId;

    /**
     * 执行器希望领取的任务类型。
     *
     * <p>为空表示领取任意类型；有值时只领取对应类型任务。
     */
    private String taskType;

    /**
     * 租约秒数。
     *
     * <p>执行器需要在租约到期前持续心跳续租；如果长时间不续租，系统会认为执行器失联。
     */
    @Min(value = 10, message = "租约秒数不能小于 10")
    @Max(value = 3600, message = "租约秒数不能大于 3600")
    private Long leaseSeconds;
}
