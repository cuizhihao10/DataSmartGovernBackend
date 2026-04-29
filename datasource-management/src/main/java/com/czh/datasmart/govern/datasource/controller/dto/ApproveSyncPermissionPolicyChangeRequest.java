package com.czh.datasmart.govern.datasource.controller.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * @Author : Cui
 * @Date: 2026/4/24 22:03
 * @Description DataSmart Govern Backend - ApproveSyncPermissionPolicyChangeRequest.java
 * @Version:1.0.0
 *
 * 审批权限绑定变更申请请求。
 * 当前支持两种结果：通过并执行、驳回并结束。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ApproveSyncPermissionPolicyChangeRequest extends SyncActionRequest {

    /**
     * 是否通过审批。
     */
    @NotNull(message = "approved 不能为空")
    private Boolean approved;

    /**
     * 审批意见。
     */
    private String approvalComment;
}
