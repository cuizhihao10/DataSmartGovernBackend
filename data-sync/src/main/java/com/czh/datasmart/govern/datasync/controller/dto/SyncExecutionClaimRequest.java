/**
 * @Author : Cui
 * @Date: 2026/05/08 21:52
 * @Description DataSmart Govern Backend - SyncExecutionClaimRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 同步执行认领请求。
 *
 * <p>claim 是执行器协议的入口：执行器不需要提前知道 executionId，而是向 data-sync 请求下一条可执行记录。
 */
@Data
public class SyncExecutionClaimRequest {

    @NotBlank(message = "执行器 ID 不能为空")
    private String executorId;

    /**
     * 可选租户过滤。
     * 租户专属 worker 可以只认领某个租户的任务，平台级 worker 可不传。
     */
    private Long tenantId;

    /**
     * 租约秒数。
     * 调用方不传时服务端使用默认值，避免 worker 无限占用 execution。
     */
    private Long leaseSeconds;
}
