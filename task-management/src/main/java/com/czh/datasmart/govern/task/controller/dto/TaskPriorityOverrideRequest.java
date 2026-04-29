package com.czh.datasmart.govern.task.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * @Author : Cui
 * @Date: 2026/04/27 00:55
 * @Description DataSmart Govern Backend - TaskPriorityOverrideRequest.java
 * @Version:1.0.0
 *
 * 任务优先级覆盖请求。
 *
 * <p>优先级调整是典型运营控制能力。
 * 真实产品中，普通用户通常只能在创建任务时选择优先级；运营人员或管理员则可能在队列拥堵、
 * 客户 SLA 风险、紧急修复任务插队等场景中临时覆盖优先级。
 */
@Data
public class TaskPriorityOverrideRequest {

    /**
     * 目标优先级。
     *
     * <p>取值由 TaskPriority.normalize 统一归一和校验，当前支持 HIGH、MEDIUM、LOW。
     */
    @NotBlank(message = "目标优先级不能为空")
    private String priority;

    /**
     * 调整原因。
     *
     * <p>优先级变更会影响调度公平性，所以必须留下原因，方便客户现场解释为什么某些任务被提前或延后。
     */
    @Size(max = 500, message = "调整原因不能超过 500 个字符")
    private String reason;
}
