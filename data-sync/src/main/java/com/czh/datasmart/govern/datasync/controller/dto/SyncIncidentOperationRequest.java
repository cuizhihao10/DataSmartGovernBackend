/**
 * @Author : Cui
 * @Date: 2026/05/08 22:41
 * @Description DataSmart Govern Backend - SyncIncidentOperationRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 同步事故处理请求。
 *
 * <p>acknowledge、assign、resolve、close 共用这个 DTO。
 * 这样前端在事故详情页上可以用同一套表单模型承载备注、负责人和解决摘要。
 */
@Data
public class SyncIncidentOperationRequest {

    /** 操作备注，用于审计记录和后续复盘。 */
    @Size(max = 1000, message = "操作备注不能超过 1000 个字符")
    private String note;

    /** 分派负责人 ID，assign 动作必填。 */
    private Long assignedOperatorId;

    /** 分派负责人角色，assign 动作可选，便于后续按运营组或值班组统计。 */
    @Size(max = 64, message = "负责人角色不能超过 64 个字符")
    private String assignedOperatorRole;

    /** 解决或关闭摘要。 */
    @Size(max = 2000, message = "解决摘要不能超过 2000 个字符")
    private String resolutionSummary;
}
