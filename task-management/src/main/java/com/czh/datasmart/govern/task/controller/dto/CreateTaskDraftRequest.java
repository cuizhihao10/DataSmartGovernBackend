/**
 * @Author : Cui
 * @Date: 2026/05/25 00:01
 * @Description DataSmart Govern Backend - CreateTaskDraftRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.controller.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 创建任务草稿请求。
 *
 * <p>该 DTO 面向“草稿创建”而不是“真实任务创建”。
 * 它允许 Agent Runtime、任务模板、人工控制台先提交一个待审阅对象，
 * 但不会让任务进入执行器可认领队列。</p>
 */
@Data
public class CreateTaskDraftRequest {

    @NotBlank(message = "草稿名称不能为空")
    private String name;

    private String description;

    @NotBlank(message = "任务类型不能为空")
    private String type;

    @Min(value = 0, message = "租户 ID 不能小于 0")
    private Long tenantId;

    @Min(value = 0, message = "负责人 ID 不能小于 0")
    private Long ownerId;

    @Min(value = 0, message = "项目 ID 不能小于 0")
    private Long projectId;

    private String params;

    private String priority;

    @Min(value = 0, message = "最大重试次数不能小于 0")
    private Integer maxRetryCount;

    @Min(value = 0, message = "最大连续延迟次数不能小于 0")
    private Integer maxDeferCount;

    /**
     * 来源类型，例如 AGENT、TEMPLATE、MANUAL、API。
     */
    private String sourceType;

    /**
     * 来源引用，例如 Agent auditId、模板 ID、外部工单号。
     */
    private String sourceRef;
}
