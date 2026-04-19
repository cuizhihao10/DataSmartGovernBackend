package com.czh.datasmart.govern.datasource.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * @Author : Cui
 * @Date: 2026/4/19 19:18
 * @Description DataSmart Govern Backend - SyncExecutorHeartbeatRequest.java
 * @Version:1.0.0
 *
 * 执行器心跳请求。
 * 心跳接口和进度回写接口分离的原因是：
 * 1. 心跳频率通常更高，如果每次都携带完整进度会放大写入压力。
 * 2. 有些执行阶段暂时没有新增进度，但仍需要告诉平台“我还活着”。
 * 3. 心跳更偏租约保活，进度回写更偏业务统计，两者关注点不同。
 */
@Data
public class SyncExecutorHeartbeatRequest {

    /**
     * 操作者 ID。
     */
    @NotNull(message = "actorId 不能为空")
    private Long actorId;

    /**
     * 操作者角色。
     */
    @NotBlank(message = "actorRole 不能为空")
    private String actorRole;

    /**
     * 执行器实例标识。
     */
    @NotBlank(message = "executorId 不能为空")
    private String executorId;

    /**
     * 同步任务 ID。
     */
    @NotNull(message = "taskId 不能为空")
    private Long taskId;

    /**
     * 执行记录 ID。
     */
    @NotNull(message = "executionId 不能为空")
    private Long executionId;
}
