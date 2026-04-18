package com.czh.datasmart.govern.task.controller.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * @Author : Cui
 * @Date: 2026/4/18 22:12
 * @Description DataSmart Govern Backend - TaskFailRequest.java
 * @Version:1.0.0
 *
 * 标记任务失败时的请求体。
 * 失败信息非常关键，因为它直接决定后续是否重试、如何排障以及是否需要人工介入。
 */
@Data
public class TaskFailRequest {

    /**
     * 失败原因说明。
     * 当前要求非空，目的是强制每一次失败都留下最基本的故障上下文。
     */
    @NotBlank(message = "失败原因不能为空")
    private String errorMessage;
}
