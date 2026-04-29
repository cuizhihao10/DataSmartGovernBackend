package com.czh.datasmart.govern.datasource.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * @Author : Cui
 * @Date: 2026/4/18 23:18
 * @Description DataSmart Govern Backend - SyncScheduleRequest.java
 * @Version:1.0.0
 *
 * 调度配置请求。
 */
@Data
public class SyncScheduleRequest {

    @NotNull(message = "actorId 不能为空")
    private Long actorId;

    @NotBlank(message = "actorRole 不能为空")
    private String actorRole;

    /**
     * 调度动作所属租户。
     */
    @NotNull(message = "actorTenantId 不能为空")
    private Long actorTenantId;

    /**
     * 调度策略 JSON。
     */
    @NotBlank(message = "scheduleConfig 不能为空")
    private String scheduleConfig;

    /**
     * 下一次运行时间。
     * 这个字段允许前端和调度中心在第一次创建计划时显式下发目标时间点。
     */
    @NotNull(message = "nextRunAt 不能为空")
    private LocalDateTime nextRunAt;

    private String note;
}
