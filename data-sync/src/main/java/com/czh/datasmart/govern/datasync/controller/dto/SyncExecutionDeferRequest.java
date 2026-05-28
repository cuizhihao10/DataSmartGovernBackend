/**
 * @Author : Cui
 * @Date: 2026/05/08 21:52
 * @Description DataSmart Govern Backend - SyncExecutionDeferRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 同步执行延迟回队列请求。
 *
 * <p>defer 用于容量不足、目标端限流、维护窗口等“暂时不能执行但不应算业务失败”的场景。
 */
@Data
public class SyncExecutionDeferRequest {

    @NotBlank(message = "执行器 ID 不能为空")
    private String executorId;

    private Long delaySeconds;
    private String reason;

    /**
     * 幂等键。
     *
     * <p>defer 会修改 execution 状态并清理租约，因此比 heartbeat 更需要幂等保护。
     * 如果执行器超时后重复提交相同 defer，应复用同一个 idempotencyKey。
     */
    private String idempotencyKey;
}
