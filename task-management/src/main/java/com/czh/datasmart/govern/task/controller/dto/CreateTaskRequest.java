package com.czh.datasmart.govern.task.controller.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 创建任务请求
 */
@Data
public class CreateTaskRequest {

    @NotBlank(message = "task name must not be blank")
    private String name;

    private String description;

    @NotBlank(message = "task type must not be blank")
    private String type;

    private String params;

    private String priority;

    private Integer maxRetryCount;
}
