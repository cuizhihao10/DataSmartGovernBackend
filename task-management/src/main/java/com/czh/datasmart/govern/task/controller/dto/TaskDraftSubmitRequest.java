/**
 * @Author : Cui
 * @Date: 2026/05/25 00:02
 * @Description DataSmart Govern Backend - TaskDraftSubmitRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.controller.dto;

import lombok.Data;

/**
 * 提交任务草稿审批请求。
 */
@Data
public class TaskDraftSubmitRequest {

    /**
     * 提交说明，建议写清楚为什么要执行该任务、影响范围和期望结果。
     */
    private String comment;
}
