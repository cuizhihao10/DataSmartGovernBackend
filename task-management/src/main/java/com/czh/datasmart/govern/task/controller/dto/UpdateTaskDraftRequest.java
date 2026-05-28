/**
 * @Author : Cui
 * @Date: 2026/05/25 00:02
 * @Description DataSmart Govern Backend - UpdateTaskDraftRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.controller.dto;

import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 更新任务草稿请求。
 *
 * <p>只允许更新仍处于 DRAFT 或 REJECTED 的草稿。
 * 已提交审批、已审批通过或已转换真实任务的草稿不能被随意修改，
 * 否则审批内容和最终转换内容会不一致。</p>
 */
@Data
public class UpdateTaskDraftRequest {

    private String name;
    private String description;
    private String type;
    private String params;
    private String priority;

    @Min(value = 0, message = "负责人 ID 不能小于 0")
    private Long ownerId;

    @Min(value = 0, message = "项目 ID 不能小于 0")
    private Long projectId;

    @Min(value = 0, message = "最大重试次数不能小于 0")
    private Integer maxRetryCount;

    @Min(value = 0, message = "最大连续延迟次数不能小于 0")
    private Integer maxDeferCount;
}
