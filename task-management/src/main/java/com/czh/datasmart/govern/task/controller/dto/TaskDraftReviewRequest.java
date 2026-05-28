/**
 * @Author : Cui
 * @Date: 2026/05/25 00:03
 * @Description DataSmart Govern Backend - TaskDraftReviewRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.controller.dto;

import lombok.Data;

/**
 * 任务草稿审批请求。
 */
@Data
public class TaskDraftReviewRequest {

    /**
     * 审批说明或拒绝原因。
     */
    private String comment;
}
