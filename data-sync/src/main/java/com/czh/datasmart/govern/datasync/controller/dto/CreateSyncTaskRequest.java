/**
 * @Author : Cui
 * @Date: 2026/05/07 21:28
 * @Description DataSmart Govern Backend - CreateSyncTaskRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 创建同步任务请求。
 *
 * <p>任务来自模板，但任务拥有自己的负责人、优先级、调度配置和运行模式。
 * 这种拆分可以支撑“同一个模板生成多个运营任务”，例如每日同步、一次性补数、紧急回放各自独立管理。
 */
@Data
public class CreateSyncTaskRequest {

    private Long tenantId;

    /**
     * 项目 ID。
     *
     * <p>默认继承模板项目；如果请求显式传入，服务端会校验它必须与模板一致。
     */
    private Long projectId;

    /**
     * 工作空间 ID。
     *
     * <p>默认继承模板工作空间；如果请求显式传入，服务端会校验它必须与模板一致。
     */
    private Long workspaceId;

    @NotNull(message = "模板 ID 不能为空")
    private Long templateId;

    private String name;
    private String description;
    private String priority;
    private String scheduleConfig;
    private String runMode;
    private Long ownerId;
}
