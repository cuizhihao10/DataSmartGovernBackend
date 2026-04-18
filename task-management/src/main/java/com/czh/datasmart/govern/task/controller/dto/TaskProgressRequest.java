package com.czh.datasmart.govern.task.controller.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 更新任务进度请求
 */
@Data
public class TaskProgressRequest {

    @NotNull(message = "progress must not be null")
    @Min(value = 0, message = "progress must be greater than or equal to 0")
    @Max(value = 100, message = "progress must be less than or equal to 100")
    private Integer progress;

    private String checkpoint;
}
