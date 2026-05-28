package com.czh.datasmart.govern.task.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
     * 当前执行 run ID。
     *
     * <p>完成回调属于终态写入，必须绑定到当前 run。
     * 如果旧 run 在超时恢复后才迟到完成，不应再把已经被新 run 接管的任务改成 SUCCESS。
     */
    @NotNull(message = "执行 runId 不能为空")
    private Long runId;

    /**
     * 执行器实例 ID。
     *
     * <p>必须与任务当前租约持有者一致，防止其他执行器把未持有的任务标记完成。
     */
    @NotBlank(message = "执行器 ID 不能为空")
    private String executorId;

    /**
     * 幂等键。
     *
     * <p>完成回调常见于“任务已成功但 HTTP 响应丢失”的场景。
     * 调用方重试时应复用该键，task-management 可以识别重复完成请求并避免重复推进状态。
     */
    @NotBlank(message = "幂等键不能为空")
    private String idempotencyKey;

    /**
     * 任务完成结果。
     * 可以是成功摘要、输出说明、结果位置等，当前要求非空，避免出现“已完成但无结果说明”的空洞记录。
     */
    @NotBlank(message = "任务完成结果不能为空")
    private String result;
}
