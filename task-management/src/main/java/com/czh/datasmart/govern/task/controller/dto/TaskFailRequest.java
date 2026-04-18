package com.czh.datasmart.govern.task.controller.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 任务失败请求
 */
@Data
public class TaskFailRequest {

    @NotBlank(message = "error message must not be blank")
    private String errorMessage;
}
