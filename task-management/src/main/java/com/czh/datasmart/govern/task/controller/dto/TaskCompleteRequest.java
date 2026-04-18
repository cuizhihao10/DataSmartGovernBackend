package com.czh.datasmart.govern.task.controller.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 任务完成请求
 */
@Data
public class TaskCompleteRequest {

    @NotBlank(message = "task result must not be blank")
    private String result;
}
