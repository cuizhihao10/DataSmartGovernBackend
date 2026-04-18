package com.czh.datasmart.govern.task.controller.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * @Author : Cui
 * @Date: 2026/4/18 22:12
 * @Description DataSmart Govern Backend - TaskCompleteRequest.java
 * @Version:1.0.0
 *
 * 标记任务完成时的请求体。
 * 完成动作除了把状态改为 SUCCESS，还需要沉淀本次执行的结果摘要，
 * 这样后续列表页、详情页、审计页都能快速看到“任务做完后产出了什么”。
 */
@Data
public class TaskCompleteRequest {

    /**
     * 任务完成结果。
     * 可以是成功摘要、输出说明、结果位置等，当前要求非空，避免出现“已完成但无结果说明”的空洞记录。
     */
    @NotBlank(message = "任务完成结果不能为空")
    private String result;
}
