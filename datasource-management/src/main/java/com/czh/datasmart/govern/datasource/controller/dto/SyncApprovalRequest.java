package com.czh.datasmart.govern.datasource.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * @Author : Cui
 * @Date: 2026/4/18 23:18
 * @Description DataSmart Govern Backend - SyncApprovalRequest.java
 * @Version:1.0.0
 *
 * 审批请求。
 */
@Data
public class SyncApprovalRequest {

    @NotNull(message = "actorId 不能为空")
    private Long actorId;

    @NotBlank(message = "actorRole 不能为空")
    private String actorRole;

    @NotNull(message = "approved 不能为空")
    private Boolean approved;

    private String comment;
}
